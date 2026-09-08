package com.marketplace.pricing;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * L26 (feature-expansion roadmap §5, Week 3): the host's price-calendar
 * management — the CRUD surface the roadmap calls "نقاط CRUD للمضيف".
 * Every write (and the calendar read) resolves the listing through the
 * {@link ListingPriceProvider} port (404 for an unknown listing — the
 * catalog module stays the listing owner) and then verifies the caller
 * owns it (403 otherwise) with the {@code MediaService}/{@code
 * CatalogService.verifyOwnership} convention: admin passes, otherwise the
 * provider profile behind the listing's {@code provider_id} — a user id
 * (the A1 cross-module space) — must be the current user's.
 *
 * <p>Overlap policy (roadmap criterion 2): two seasonal ranges of the same
 * listing that actually intersect are rejected with 409
 * ({@link SeasonalRate#overlaps}); two ADJACENT ranges sharing a boundary
 * (first {@code toDate ==} second {@code fromDate}) are legal — open
 * intervals. The check walks the sibling rows at the service seam in the
 * same transaction; the V41 {@code ex_seasonal_rates_live_listing_range}
 * EXCLUDE constraint is the RACE backstop (two concurrent writers both
 * passing the walk) — a violation of THAT constraint is translated into
 * the same 409 here while every other database error surfaces unchanged
 * (the losing request of a true race and the rejected sequential one get
 * the identical taxonomy).
 *
 * <p>Cache (roadmap criterion 4): every calendar write publishes
 * {@link CacheInvalidationRequested} for the {@code pricing-calculations}
 * namespace — the same eviction the {@code PricingRule} writes already
 * emit, so no cached windowed quote outlives a calendar change.
 *
 * <p>Deletion semantics: the {@code BaseEntity} @SoftDelete rule — the
 * row is hidden ({@code is_deleted = true}), never physically removed,
 * and Envers keeps the full revision history (V41 {@code _aud} tables).
 *
 * <p>Weekend-rule upsert concurrency (CodeRabbit round 2, the L22
 * precedent applied): the write runs inside a dedicated
 * {@code TransactionTemplate(REQUIRES_NEW)} — when two concurrent PUTs
 * both find no live rule and both try to INSERT, the V41 partial unique
 * index lets exactly one win; the loser's single bounded retry re-runs in
 * a FRESH transaction (the failed one already rolled back), finds the
 * winner's live row, and re-tunes it — the PUT is idempotent, so the
 * losing request completes as the update it semantically was. A second
 * race within the retry surfaces as the honest final exception.
 */
@Service
@Transactional
public class ListingPriceCalendarService {

    private final ListingWeekendRuleRepository weekendRuleRepository;
    private final SeasonalRateRepository seasonalRateRepository;
    private final ListingPriceProvider listingPriceProvider;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderLookupPort providerLookupPort;
    private final ApplicationEventPublisher eventPublisher;
    /** The L22 upsert transaction: REQUIRES_NEW, one retry on a lost insert race. */
    private final TransactionTemplate upsertTransaction;

    private static final Set<String> PRICING_CACHE_NAMES = Set.of("pricing-calculations");

    public ListingPriceCalendarService(ListingWeekendRuleRepository weekendRuleRepository,
                                       SeasonalRateRepository seasonalRateRepository,
                                       ListingPriceProvider listingPriceProvider,
                                       CurrentUserProvider currentUserProvider,
                                       ProviderLookupPort providerLookupPort,
                                       ApplicationEventPublisher eventPublisher,
                                       PlatformTransactionManager transactionManager) {
        this.weekendRuleRepository = weekendRuleRepository;
        this.seasonalRateRepository = seasonalRateRepository;
        this.listingPriceProvider = listingPriceProvider;
        this.currentUserProvider = currentUserProvider;
        this.providerLookupPort = providerLookupPort;
        this.eventPublisher = eventPublisher;
        this.upsertTransaction = new TransactionTemplate(transactionManager);
        this.upsertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The listing's whole calendar — the host's own view of the rules the
     * effective-price engine consumes. Unobserved per the inventory policy
     * (reads ride http.server.requests; only commands are @Observed).
     */
    @Transactional(readOnly = true)
    public ListingCalendarResponse getCalendar(UUID listingId, Authentication authentication) {
        requireOwnedListing(listingId, authentication);
        ListingWeekendRule weekendRule = weekendRuleRepository.findByListingId(listingId).orElse(null);
        List<SeasonalRateResponse> rates = seasonalRateRepository
                .findByListingIdOrderByFromDateAsc(listingId).stream()
                .map(ListingPriceCalendarService::toSeasonalRateResponse)
                .toList();
        return new ListingCalendarResponse(listingId,
                weekendRule == null ? null : toWeekendRuleResponse(weekendRule),
                rates);
    }

    /**
     * Upsert: the single weekend rule of the listing — created when absent,
     * re-tuned when present (the partial unique index of V41 keeps exactly
     * one LIVE row; a soft-deleted predecessor never blocks re-creation).
     * The write runs in its own REQUIRES_NEW transaction with one bounded
     * retry on a lost insert race (the class javadoc's L22 pattern).
     */
    @Observed(name = "pricing.calendar.weekend.upsert")
    public WeekendRuleResponse upsertWeekendRule(UUID listingId, BigDecimal multiplier,
                                                 Authentication authentication) {
        requireOwnedListing(listingId, authentication);
        try {
            return upsertWeekendRuleInNewTransaction(listingId, multiplier);
        } catch (DataIntegrityViolationException lostInsertRace) {
            if (!isWeekendRuleInsertRace(lostInsertRace)) {
                throw lostInsertRace;
            }
            // A concurrent PUT inserted the single live rule first: the
            // partial unique index already rolled this inner transaction
            // back. Retry ONCE in a fresh transaction — the winner's row now
            // exists, so the same request takes the re-tune path.
            return upsertWeekendRuleInNewTransaction(listingId, multiplier);
        }
    }

    private WeekendRuleResponse upsertWeekendRuleInNewTransaction(UUID listingId, BigDecimal multiplier) {
        return upsertTransaction.execute(status -> {
            ListingWeekendRule rule = weekendRuleRepository.findByListingId(listingId).orElse(null);
            if (rule == null) {
                rule = ListingWeekendRule.create(listingId, multiplier);
            } else {
                rule.changeMultiplier(multiplier);
            }
            ListingWeekendRule saved = weekendRuleRepository.save(rule);
            // Force the INSERT (and the unique-index check) INSIDE this
            // frame — a deferred flush would raise the race past the retry.
            weekendRuleRepository.flush();
            eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
            return toWeekendRuleResponse(saved);
        });
    }

    /**
     * The V41 partial unique index's identity — the pair (23505 unique
     * violation + this index's name in the message); Hibernate 7 leaves
     * getConstraintName() null (measured live), the SQLState carries the class.
     */
    private boolean isWeekendRuleInsertRace(DataIntegrityViolationException ex) {
        if (ex.getCause() instanceof ConstraintViolationException violation) {
            return "23505".equals(violation.getSQLState())
                    && ex.getMessage() != null
                    && ex.getMessage().contains("uq_listing_weekend_rules_live_listing");
        }
        return false;
    }

    /**
     * Removes the weekend rule (soft delete) — the listing returns to the
     * flat model for weekend days unless seasonal ranges cover them.
     */
    @Observed(name = "pricing.calendar.weekend.delete")
    public void deleteWeekendRule(UUID listingId, Authentication authentication) {
        requireOwnedListing(listingId, authentication);
        ListingWeekendRule rule = weekendRuleRepository.findByListingId(listingId)
                .orElseThrow(() -> new ResourceNotFoundException("WeekendRule", listingId));
        weekendRuleRepository.deleteById(rule.getId());
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
    }

    /**
     * The exclusion constraint of V41 — the race backstop's identity.
     */
    static final String LIVE_RANGE_EXCLUSION_CONSTRAINT = "ex_seasonal_rates_live_listing_range";

    /**
     * Adds a seasonal range. Rejected with 409 when it actually overlaps a
     * live sibling range; adjacent-on-a-boundary ranges are legal.
     */
    @Observed(name = "pricing.calendar.seasonal.create")
    public SeasonalRateResponse addSeasonalRate(UUID listingId, LocalDate fromDate, LocalDate toDate,
                                                long priceCents, Authentication authentication) {
        requireOwnedListing(listingId, authentication);
        SeasonalRate candidate = SeasonalRate.create(listingId, fromDate, toDate, priceCents);
        SeasonalRate saved;
        try {
            assertNoOverlap(listingId, candidate, null);
            saved = seasonalRateRepository.save(candidate);
            // Force the INSERT (and the exclusion-constraint check) INSIDE
            // this frame — a deferred flush would raise the violation at
            // COMMIT time, past the translation seam.
            seasonalRateRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw overlapConflictOrRethrow(ex, listingId);
        }
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
        return toSeasonalRateResponse(saved);
    }

    /**
     * Re-tunes one seasonal range (dates and/or price) — the same overlap
     * policy, with the updated row itself excluded from the sibling walk.
     */
    @Observed(name = "pricing.calendar.seasonal.update")
    public SeasonalRateResponse updateSeasonalRate(UUID listingId, UUID rateId,
                                                   LocalDate fromDate, LocalDate toDate, long priceCents,
                                                   Authentication authentication) {
        requireOwnedListing(listingId, authentication);
        SeasonalRate rate = seasonalRateRepository.findById(rateId)
                .filter(r -> listingId.equals(r.getListingId()))
                .orElseThrow(() -> new ResourceNotFoundException("SeasonalRate", rateId));
        rate.change(fromDate, toDate, priceCents);
        SeasonalRate saved;
        try {
            // The walk's SELECT AUTO-FLUSHES the dirty UPDATE first
            // (FlushMode.AUTO: a query over the same table flushes pending
            // changes) — the constraint can therefore fire at the WALK, not
            // only at the save; both sit inside the translation frame.
            assertNoOverlap(listingId, rate, rateId);
            saved = seasonalRateRepository.save(rate);
            seasonalRateRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw overlapConflictOrRethrow(ex, listingId);
        }
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
        return toSeasonalRateResponse(saved);
    }

    /**
     * Removes one seasonal range (soft delete).
     */
    @Observed(name = "pricing.calendar.seasonal.delete")
    public void deleteSeasonalRate(UUID listingId, UUID rateId, Authentication authentication) {
        requireOwnedListing(listingId, authentication);
        SeasonalRate rate = seasonalRateRepository.findById(rateId)
                .filter(r -> listingId.equals(r.getListingId()))
                .orElseThrow(() -> new ResourceNotFoundException("SeasonalRate", rateId));
        seasonalRateRepository.deleteById(rate.getId());
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
    }

    /**
     * The overlap walk — every LIVE sibling of the listing except the row
     * being changed (identified by {@code excludeId}) must be disjoint
     * from the candidate. Adjacent ranges sharing a boundary pass (open
     * intervals — {@link SeasonalRate#overlaps}).
     */
    private void assertNoOverlap(UUID listingId, SeasonalRate candidate, UUID excludeId) {
        for (SeasonalRate sibling : seasonalRateRepository.findByListingIdOrderByFromDateAsc(listingId)) {
            if (sibling.getId().equals(excludeId)) {
                continue;
            }
            if (sibling.overlaps(candidate)) {
                throw SeasonalRate.overlapConflict(listingId);
            }
        }
    }

    /**
     * Persists nothing by itself — the translation seam: a violation of the
     * V41 live-range EXCLUDE constraint (the race backstop — a concurrent
     * writer committed an overlapping live range between our walk and our
     * flush, or the AUTO-FLUSH of a dirty update hit it at the walk) becomes
     * the SAME 409 ConflictException the sequential rejection answers with.
     * Any OTHER integrity violation (a different constraint) is rethrown
     * unchanged — the translation must not mask unrelated database errors.
     */
    private RuntimeException overlapConflictOrRethrow(DataIntegrityViolationException ex, UUID listingId) {
        if (ex.getCause() instanceof ConstraintViolationException violation) {
            // Hibernate 7 does not parse the constraint NAME for PostgreSQL
            // exclusion violations — measured live: getConstraintName() is
            // null while the SQLState carries 23P01 (PostgreSQL's
            // exclusion_violation, a class no other violation uses). The
            // pair (23P01 + this constraint's name in the message) is the
            // precise identity of OUR backstop.
            if ("23P01".equals(violation.getSQLState())
                    && ex.getMessage() != null
                    && ex.getMessage().contains(LIVE_RANGE_EXCLUSION_CONSTRAINT)) {
                return SeasonalRate.overlapConflict(listingId);
            }
        }
        return ex;
    }

    /**
     * Resolves the listing (404 through the catalog-owned port) and then
     * verifies the caller owns it (the {@code MediaService} convention —
     * same rule as {@code CatalogService.verifyOwnership}).
     */
    private void requireOwnedListing(UUID listingId, Authentication authentication) {
        ListingPriceProvider.ListingInfo info = listingPriceProvider.getListingInfo(listingId);
        verifyOwnership(info.providerId(), authentication);
    }

    private void verifyOwnership(UUID providerId, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (currentUserProvider.isAdmin(authentication)) {
            return;
        }
        // A1: the providerId of a listing is a user id, so compare against
        // the user-owned profile via findByUserId (the house convention).
        providerLookupPort.findByUserId(providerId)
                .filter(provider -> provider.userId() != null && provider.userId().equals(currentUserId))
                .orElseThrow(() -> new AccessDeniedException("You do not own this listing"));
    }

    private static WeekendRuleResponse toWeekendRuleResponse(ListingWeekendRule rule) {
        return new WeekendRuleResponse(rule.getId(), rule.getListingId(), rule.getMultiplier(),
                rule.getCreatedAt(), rule.getUpdatedAt());
    }

    private static SeasonalRateResponse toSeasonalRateResponse(SeasonalRate rate) {
        return new SeasonalRateResponse(rate.getId(), rate.getListingId(), rate.getFromDate(),
                rate.getToDate(), rate.getPriceCents(), rate.getCreatedAt(), rate.getUpdatedAt());
    }
}
