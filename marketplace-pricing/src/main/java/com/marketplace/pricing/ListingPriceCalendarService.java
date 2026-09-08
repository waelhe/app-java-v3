package com.marketplace.pricing;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * same transaction (the V41 header documents why the schema carries no
 * exclusion constraint).
 *
 * <p>Cache (roadmap criterion 4): every calendar write publishes
 * {@link CacheInvalidationRequested} for the {@code pricing-calculations}
 * namespace — the same eviction the {@code PricingRule} writes already
 * emit, so no cached windowed quote outlives a calendar change.
 *
 * <p>Deletion semantics: the {@code BaseEntity} @SoftDelete rule — the
 * row is hidden ({@code is_deleted = true}), never physically removed,
 * and Envers keeps the full revision history (V41 {@code _aud} tables).
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

    private static final Set<String> PRICING_CACHE_NAMES = Set.of("pricing-calculations");

    public ListingPriceCalendarService(ListingWeekendRuleRepository weekendRuleRepository,
                                       SeasonalRateRepository seasonalRateRepository,
                                       ListingPriceProvider listingPriceProvider,
                                       CurrentUserProvider currentUserProvider,
                                       ProviderLookupPort providerLookupPort,
                                       ApplicationEventPublisher eventPublisher) {
        this.weekendRuleRepository = weekendRuleRepository;
        this.seasonalRateRepository = seasonalRateRepository;
        this.listingPriceProvider = listingPriceProvider;
        this.currentUserProvider = currentUserProvider;
        this.providerLookupPort = providerLookupPort;
        this.eventPublisher = eventPublisher;
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
     */
    @Observed(name = "pricing.calendar.weekend.upsert")
    public WeekendRuleResponse upsertWeekendRule(UUID listingId, BigDecimal multiplier,
                                                 Authentication authentication) {
        requireOwnedListing(listingId, authentication);
        ListingWeekendRule rule = weekendRuleRepository.findByListingId(listingId).orElse(null);
        if (rule == null) {
            rule = ListingWeekendRule.create(listingId, multiplier);
        } else {
            rule.changeMultiplier(multiplier);
        }
        ListingWeekendRule saved = weekendRuleRepository.save(rule);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
        return toWeekendRuleResponse(saved);
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
     * Adds a seasonal range. Rejected with 409 when it actually overlaps a
     * live sibling range; adjacent-on-a-boundary ranges are legal.
     */
    @Observed(name = "pricing.calendar.seasonal.create")
    public SeasonalRateResponse addSeasonalRate(UUID listingId, LocalDate fromDate, LocalDate toDate,
                                                long priceCents, Authentication authentication) {
        requireOwnedListing(listingId, authentication);
        SeasonalRate candidate = SeasonalRate.create(listingId, fromDate, toDate, priceCents);
        assertNoOverlap(listingId, candidate, null);
        SeasonalRate saved = seasonalRateRepository.save(candidate);
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
        assertNoOverlap(listingId, rate, rateId);
        SeasonalRate saved = seasonalRateRepository.save(rate);
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
