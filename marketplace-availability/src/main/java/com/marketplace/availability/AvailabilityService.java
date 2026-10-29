package com.marketplace.availability;

import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.observation.annotation.Observed;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class AvailabilityService implements AvailabilityPort {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final AvailabilitySlotRepository repository;
    private final ProviderAvailabilityRuleRepository ruleRepository;
    private final ProviderTimeOffRepository timeOffRepository;
    private final ApplicationEventPublisher eventPublisher;
    /**
     * Row 11 (agent findings 2026-10-25 — CWE-834): the compatible
     * publication bound — the slot-span cap that keeps oversized windows
     * out of the stored slot set (and therefore out of the exact-match
     * booking path) from the first write.
     */
    private final AvailabilityProperties properties;
    /**
     * CodeRabbit round 1 on PR #471 (adopted from the root): the per-rule
     * generation unit lives in its own bean behind a proxied
     * {@code REQUIRES_NEW} transaction — a flush-time constraint failure
     * rolls back exactly one rule instead of poisoning the listener's whole
     * transaction (the rollback-only trap the catch cannot recover from).
     */
    private final AvailabilitySlotGenerator slotGenerator;
    private final com.marketplace.shared.api.ProviderLookupPort providerLookupPort;

    /**
     * Row 11 (agent findings 2026-10-26 14:33 export): the provider
     * eligibility policy — only a VERIFIED profile holds bookable
     * availability. The publication gate ({@code createSlot}) and the
     * claim gate ({@code bookSlot}) both refuse the honest 409 for a
     * SUSPENDED (or pre-verification PENDING) profile: the finding's own
     * recommendation ("Enforce provider eligibility before accepting new
     * bookings and claims, and prevent suspended profiles from publishing
     * or generating slots"). The house precedent verbatim — the public
     * page's listings block "is served only for VERIFIED profiles" and
     * listing "creation requires VERIFIED"; the providerId universe is
     * the users.id space, resolved through the own-profile seam
     * {@code findByUserId} (the AuthHelper discipline). The gate is
     * deliberately NOT folded into the {@code @Cacheable isAvailable}
     * read — the eligibility fact is not a slot fact, and the claim edge
     * is the authority that decides.
     */
    private static final String PROVIDER_STATUS_VERIFIED = "VERIFIED";

    /**
     * The eligibility policy's ONE gate — public through
     * {@link com.marketplace.shared.api.AvailabilityPort} since the CodeRabbit
     * review round on #741's part 4 (2026-10-28): the booking CREATE path now
     * holds the same authority the claim edge always applied (see
     * {@link #bookSlot}'s row-11 comment for the policy itself).
     */
    @Override
    public void requireEligibleProvider(UUID providerId) {
        boolean eligible = providerLookupPort.findByUserId(providerId)
                .map(summary -> PROVIDER_STATUS_VERIFIED.equals(summary.status()))
                .orElse(false);
        if (!eligible) {
            throw new ConflictException(
                    "Provider eligibility required: a verified profile alone publishes and "
                            + "receives bookable availability (suspended or unverified: " + providerId + ")");
        }
    }

    private boolean providerEligible(UUID providerId) {
        return providerLookupPort.findByUserId(providerId)
                .map(summary -> PROVIDER_STATUS_VERIFIED.equals(summary.status()))
                .orElse(false);
    }

    private static final int SLOT_GENERATION_DAYS_AHEAD = 7;

    /**
     * Caches whose content depends on availability state. L27: the
     * window-filtered {@code search-results-v4} pages (SearchService) now
     * derive from slots and time-offs, so every availability write evicts
     * them through the existing AFTER_COMMIT relay — the same freshness
     * contract listing writes already follow for
     * {@code CatalogService.CATALOG_CACHE_NAMES}. The name is shared with
     * the catalog's invalidation set and the yml {@code spring.cache.cache-names}
     * list (pinned by {@code ListingSummaryCacheContractFilesTest}).
     * Package-private: {@link AvailabilitySlotGenerator} publishes the same
     * set for the daily generation writes.
     */
    static final Set<String> AVAILABILITY_DEPENDENT_CACHE_NAMES =
            Set.of("availability", "search-results-v6");

    public AvailabilityService(AvailabilitySlotRepository repository,
                               ProviderAvailabilityRuleRepository ruleRepository,
                               ProviderTimeOffRepository timeOffRepository,
                               ApplicationEventPublisher eventPublisher,
                               AvailabilitySlotGenerator slotGenerator,
                               AvailabilityProperties properties,
                               com.marketplace.shared.api.ProviderLookupPort providerLookupPort) {
        this.repository = repository;
        this.ruleRepository = ruleRepository;
        this.timeOffRepository = timeOffRepository;
        this.eventPublisher = eventPublisher;
        this.slotGenerator = slotGenerator;
        this.properties = properties;
        this.providerLookupPort = providerLookupPort;
    }

    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    public AvailabilitySlotResponse createSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        // Row 11 (2026-10-26 14:33 export): the eligibility gate precedes
        // the write — a suspended (or unverified) profile publishes no
        // bookable slot, exactly as it lists no public inventory.
        requireEligibleProvider(providerId);
        // Row 11 (2026-10-25): the window gate precedes the write — an oversized or
        // reversed window never reaches the stored slot set.
        requireSupportedSlotWindow(startsAt, endsAt);
        AvailabilitySlot saved = repository.save(AvailabilitySlot.open(providerId, startsAt, endsAt));
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_DEPENDENT_CACHE_NAMES));
        return AvailabilitySlotResponse.from(saved);
    }

    /**
     * Row 11 (agent findings 2026-10-25 — CWE-834): the compatible
     * publication bound — the finding's recommendation verbatim ("apply
     * compatible limits when publishing availability slots"). The attack
     * chain starts HERE: a provider publishes an owned open slot without
     * a duration limit, and a compact consumer booking window matching
     * it rides the pricing module's per-date loop into tens of millions
     * of iterations. The UTC-day span of a published slot is capped at
     * the configured bound ({@code marketplace.availability.max-slot-days},
     * default 365 — compatible by construction with
     * {@code marketplace.pricing.max-stay-nights}: a booking must EXACTLY
     * match a slot, so a slot within this bound is always priceable
     * end-to-end). The reversed-window rejection is the same gate family
     * the pricing seam and the SearchCriteria record already enforce ("a
     * reversed window is never a window") — a reversed slot can never
     * match a bookable window and is pure junk state.
     */
    private void requireSupportedSlotWindow(Instant startsAt, Instant endsAt) {
        if (endsAt.isBefore(startsAt)) {
            throw new BadRequestException("Slot end must not be before slot start");
        }
        LocalDate from = LocalDate.ofInstant(startsAt, ZoneOffset.UTC);
        LocalDate to = LocalDate.ofInstant(endsAt, ZoneOffset.UTC);
        long spanDays = from.isBefore(to) ? ChronoUnit.DAYS.between(from, to) : 0L;
        long maxDays = properties.maxSlotDays();
        if (spanDays > maxDays) {
            throw new BadRequestException(
                    "Slots longer than " + maxDays + " days are not supported");
        }
    }

    @Transactional(readOnly = true)
    public List<AvailabilitySlotResponse> getSlots(UUID providerId, Instant from, Instant to) {
        return repository.findByProviderIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqual(providerId, from, to)
                .stream().map(AvailabilitySlotResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable("availability")
    public boolean isAvailable(UUID providerId, Instant startsAt, Instant endsAt) {
        boolean slotAvailable = repository.existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt);
        boolean hasTimeOffConflict = timeOffRepository.existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(providerId, endsAt, startsAt);
        return slotAvailable && !hasTimeOffConflict;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasExactAvailableSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        // Same lookup as bookSlot (findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse)
        // so create() rejects a window that confirm() could never book — the
        // sub-window case passes isAvailable (overlap) but fails here (exact).
        return repository.findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(providerId, startsAt, endsAt)
                .isPresent();
    }

    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    public ProviderAvailabilityRuleResponse createRule(UUID providerId, java.time.DayOfWeek dayOfWeek, java.time.LocalTime startTime, java.time.LocalTime endTime) {
        return ProviderAvailabilityRuleResponse.from(
                ruleRepository.save(ProviderAvailabilityRule.create(providerId, dayOfWeek, startTime, endTime)));
    }

    @ApplicationModuleListener
    public void onDayHasPassed(DayHasPassed event) {
        LocalDate date = event.getDate();
        for (int i = 0; i < SLOT_GENERATION_DAYS_AHEAD; i++) {
            generateSlotsForDate(date.plusDays(i));
        }
    }

    /**
     * Generates availability slots for a given date based on configured rules.
     *
     * <p><b>CodeRabbit round 1 on PR #471 (adopted from the root — verified
     * against the code before action):</b> the per-rule unit now runs through
     * {@link AvailabilitySlotGenerator#generateFrom} — a SEPARATE bean behind
     * a proxied {@code REQUIRES_NEW} transaction. The old loop kept every
     * rule inside this listener's single transaction: {@code save()} queues
     * the INSERT, Hibernate may flush it before a later existence query or
     * at commit, and a flush-time 23505 (V73's backstop) leaves the
     * transaction rollback-only — the per-rule catch absorbs the Java
     * exception but cannot recover the transaction, so earlier generated
     * slots rolled back and a commit-time failure escaped the catch
     * entirely, leaving the Modulith event publication entry incomplete for
     * retry. One rule, one transaction: a duplicate-window failure now rolls
     * back exactly that rule and the batch stays healthy.
     *
     * <p><b>Exception handling policy (Spring Modulith event publication
     * log):</b> the catch stays narrowed to {@link DataAccessException} — the
     * best-effort per-rule behavior (a single bad rule does not abort the
     * whole batch) while programming errors propagate to the event
     * publication log for retry. Spring Modulith Reference ("The Event
     * Publication Registry"):
     * <blockquote>
     * "Each transactional event listener is wrapped into an aspect that marks
     * that log entry as completed if the execution of the listener succeeds.
     * In case the listener fails, the log entry stays untouched so that
     * retry mechanisms can be deployed."
     * </blockquote>
     *
     * <p>Reference:
     * <a href="https://docs.spring.io/spring-modulith/reference/events.html">Spring Modulith Reference -- Event Publication Registry</a>
     * <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html">Spring Framework Reference -- Declarative Transaction Management (REQUIRES_NEW)</a>
     */
    private void generateSlotsForDate(LocalDate date) {
        List<ProviderAvailabilityRule> rules = ruleRepository.findByDayOfWeek(date.getDayOfWeek());
        if (rules.isEmpty()) {
            log.info("No availability rules configured for {}", date.getDayOfWeek());
            return;
        }
        for (ProviderAvailabilityRule rule : rules) {
            // Row 11 (2026-10-26 14:33 export): the generation gate — a
            // suspended (or unverified) profile's rules generate nothing
            // while the profile is ineligible. The rule itself stays (the
            // owner's own config — suspension is reversible: the machine's
            // own SUSPENDED -> VERIFIED edge reinstates both the profile
            // and its bookable surface).
            if (!providerEligible(rule.getProviderId())) {
                log.info("Availability rule {} skipped for {} — provider not eligible "
                        + "(suspended or unverified)", rule.getId(), rule.getProviderId());
                continue;
            }
            try {
                // Through the bean, never self-invoked: the REQUIRES_NEW
                // propagation only applies behind the proxy.
                slotGenerator.generateFrom(rule, date);
            } catch (DataAccessException e) {
                // Best-effort per rule: the rule's own transaction already
                // rolled back cleanly; log and continue the batch.
                // Programming errors (NPE, etc.) MUST propagate to the event
                // publication log so Spring Modulith can retry them.
                log.error("Failed to generate slot from rule {} (data error)", rule.getId(), e);
            }
        }
    }

    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    @Observed(name = "availability.timeoff.create")
    public ProviderTimeOffResponse createTimeOff(UUID providerId, Instant startsAt, Instant endsAt) {
        ProviderTimeOff saved = timeOffRepository.save(ProviderTimeOff.create(providerId, startsAt, endsAt));
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_DEPENDENT_CACHE_NAMES));
        return ProviderTimeOffResponse.from(saved);
    }

    @Override
    public void bookSlot(UUID providerId, Instant startsAt, Instant endsAt, UUID bookingId) {
        // Row 11 (2026-10-26 14:33 export): the CLAIM gate — booking
        // creation and confirmation both claim through this edge, so the
        // eligibility refusal here answers both surfaces (the honest 409
        // rolls the booking back; the pre-check {@code isAvailable} stays
        // a pure slot fact by design — the claim edge is the authority).
        requireEligibleProvider(providerId);
        AvailabilitySlot slot = repository
                .findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse(providerId, startsAt, endsAt)
                .orElseThrow(() -> new ConflictException("No available slot for provider " + providerId));
        // R2: the claim is one atomic entity mutation — booked flag and owner
        // set together (AvailabilitySlot.markBooked). Concurrent claims on the
        // same row are settled by the entity's @Version optimistic lock
        // (BaseEntity — the losing flush fails its transaction and rolls the
        // confirm back), with V73's one-live-row-per-window index underneath.
        slot.markBooked(bookingId);
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_DEPENDENT_CACHE_NAMES));
    }

    @Override
    public void releaseSlot(UUID providerId, Instant startsAt, Instant endsAt, UUID bookingId,
            UUID survivingClaimantId) {
        // R2: only the hold THIS booking placed is released — the ownership
        // filter makes a non-owner cancel (the PENDING sibling of the holder)
        // a no-op instead of freeing a CONFIRMED booking's window.
        // Review round on the rebased head: with a surviving active claimant
        // (the legacy-duplicates state — V73's reconciliation assigns the
        // window to the newest active booking, leaving older active
        // claimants in place), the owner's release TRANSFERS the hold to
        // that claimant instead of reopening the window — the design
        // invariant "a live window with an active booking stays booked"
        // holds for both cancellation orders.
        repository
                .findFirstByProviderIdAndStartsAtAndEndsAtAndBookedTrue(providerId, startsAt, endsAt)
                .filter(slot -> bookingId.equals(slot.getHeldByBookingId()))
                .ifPresent(slot -> {
                    if (survivingClaimantId != null) {
                        slot.markBooked(survivingClaimantId);
                    } else {
                        slot.markAvailable();
                    }
                });
        eventPublisher.publishEvent(new CacheInvalidationRequested(AVAILABILITY_DEPENDENT_CACHE_NAMES));
    }
}
