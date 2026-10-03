package com.marketplace.catalog;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the campaign's
 * owner-facing surface — create, read, pause/resume, and the immutable
 * billing history. The service lives in catalog because the campaign IS
 * the listing's paid promotion (the module that owns the listing, the
 * views and the boost ordering owns the promotion that rides them); the
 * money side crosses module boundaries ONLY through the
 * {@code AdWindowBilledEvent} (the Modulith law).
 *
 * <p><b>Creation gates (in order):</b> the listing exists, is ACTIVE,
 * and belongs to the caller (the {@code verifyOwnership} law — the
 * providerId is a user id, resolved through the user-owned profile);
 * no live campaign exists for the listing (the single-promotion Yelp
 * semantics — the service's friendly 409, the V103 partial unique index
 * the concurrency backstop); the budget is positive and the prices
 * non-negative (the request's bean validation); the duration's end is
 * strictly in the future. The campaign starts NOW ({@code AdCampaign.start})
 * — no future scheduling exists in this wave (a documented boundary, not
 * an oversight: the plan's «مدة» is the running window's end).</p>
 */
@Service
public class AdCampaignService {

    private final AdCampaignRepository campaignRepository;
    private final AdBillingChargeRepository chargeRepository;
    private final ProviderListingRepository listingRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderLookupPort providerLookupPort;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final java.time.Clock clock;

    public AdCampaignService(AdCampaignRepository campaignRepository,
                             AdBillingChargeRepository chargeRepository,
                             ProviderListingRepository listingRepository,
                             CurrentUserProvider currentUserProvider,
                             ProviderLookupPort providerLookupPort,
                             ApplicationEventPublisher eventPublisher,
                             java.time.Clock clock) {
        this.campaignRepository = campaignRepository;
        this.chargeRepository = chargeRepository;
        this.listingRepository = listingRepository;
        this.currentUserProvider = currentUserProvider;
        this.providerLookupPort = providerLookupPort;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Starts one paid promotion for the caller's ACTIVE listing. The
     * currency defaults to the house SAR and must be a valid ISO 4217
     * code ({@code Currencies.normalize}'s authority — the same validation
     * every money field carries).
     */
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public AdCampaignView create(CreateAdCampaignRequest request, Authentication authentication) {
        ProviderListing listing = listingRepository.findById(request.listingId())
                .orElseThrow(() -> new ResourceNotFoundException("Listing", request.listingId()));
        if (listing.getStatus() != ListingStatus.ACTIVE) {
            throw new ConflictException("Listing " + request.listingId()
                    + " is " + listing.getStatus() + " — only an ACTIVE listing can be promoted");
        }
        verifyOwnership(listing.getProviderId(), authentication);

        campaignRepository
                .findFirstByListingIdAndStatusOrderByIdAsc(request.listingId(), AdCampaignStatus.ACTIVE)
                .ifPresent(existing -> {
                    throw new ConflictException("Listing " + request.listingId()
                            + " already has a live campaign (" + existing.getId()
                            + ") — the single-promotion law: pause or let it end first");
                });

        Instant now = clock.instant();
        if (request.endsAt() != null && !request.endsAt().isAfter(now)) {
            throw new BadRequestException("endsAt must be strictly in the future");
        }
        AdCampaign campaign = AdCampaign.start(listing.getProviderId(), listing.getId(),
                request.budgetCents(), request.clickPriceCents(), request.impressionPriceCents(),
                request.currency(), now, request.endsAt());
        AdCampaignView view = toView(campaignRepository.save(campaign));
        // CodeRabbit W5 r1, adopted: the boost's truth changed — a live paid
        // campaign now carries the listing — and the ordered pages must not
        // serve yesterday's order (the same AFTER_COMMIT eviction law every
        // listing write and the ranking job apply).
        eventPublisher.publishEvent(new CacheInvalidationRequested(CatalogService.CATALOG_CACHE_NAMES));
        return view;
    }

    /** The caller's own campaigns — the honest consumption state on every row. */
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional(readOnly = true)
    public List<AdCampaignView> listMine(Authentication authentication) {
        UUID providerUserId = resolveProviderUserId(authentication);
        return campaignRepository.findByProviderIdOrderByCreatedAtDescIdDesc(providerUserId)
                .stream().map(this::toView).toList();
    }

    /** The owner's hold — no boost, no accrual; the paused gap never bills. */
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public AdCampaignView pause(UUID campaignId, Authentication authentication) {
        AdCampaign campaign = ownedCampaign(campaignId, authentication);
        try {
            campaign.pause();
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        // The boost's truth changed — the dark campaign's listing loses the
        // paid tier NOW, not at the next cache TTL (the eviction law above).
        eventPublisher.publishEvent(new CacheInvalidationRequested(CatalogService.CATALOG_CACHE_NAMES));
        return toView(campaign);
    }

    /**
     * The hold lifts — the billing marker jumps past the dark days (see
     * {@link AdCampaign#resume}). A campaign whose duration already ended
     * cannot lift back (CodeRabbit W5 r1, adopted): the billing run skips
     * PAUSED campaigns, so an expired-but-paused campaign would otherwise
     * return to the boost with a dead duration until the next daily run.
     */
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public AdCampaignView resume(UUID campaignId, Authentication authentication) {
        AdCampaign campaign = ownedCampaign(campaignId, authentication);
        if (campaign.getEndsAt() != null && !campaign.getEndsAt().isAfter(clock.instant())) {
            throw new ConflictException("Campaign " + campaignId
                    + " ended at " + campaign.getEndsAt() + " — its duration is over; start a new campaign");
        }
        try {
            campaign.resume(AdBillingBatchExecutor.todayUtc(clock));
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
        // The boost's truth changed — the listing regains the paid tier NOW.
        eventPublisher.publishEvent(new CacheInvalidationRequested(CatalogService.CATALOG_CACHE_NAMES));
        return toView(campaign);
    }

    /**
     * The immutable billing history — the frozen charge rows, newest
     * window first. Read-only by construction: no surface exists that
     * could ever answer anything but the frozen truth.
     */
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional(readOnly = true)
    public List<AdBillingChargeView> charges(UUID campaignId, Authentication authentication) {
        AdCampaign campaign = ownedCampaign(campaignId, authentication);
        return chargeRepository.findByCampaignIdOrderByWindowStartDescIdDesc(campaign.getId()).stream()
                .map(AdCampaignService::toChargeView).toList();
    }

    private AdCampaign ownedCampaign(UUID campaignId, Authentication authentication) {
        UUID providerUserId = resolveProviderUserId(authentication);
        return campaignRepository.findByIdAndProviderId(campaignId, providerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("AdCampaign", campaignId));
    }

    /**
     * The caller's provider id: admins bypass ownership (the
     * {@code verifyOwnership} law) — but the ads surface is the OWNER's
     * own ledger ({@code /providers/me}), so the admin path resolves the
     * first campaign/ownership the caller names instead: an admin calling
     * a /me surface without being a provider gets the honest 404.
     */
    private UUID resolveProviderUserId(Authentication authentication) {
        return currentUserProvider.getCurrentUserId(authentication);
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

    private AdCampaignView toView(AdCampaign c) {
        return new AdCampaignView(c.getId(), c.getListingId(), c.getBudgetCents(),
                c.getClickPriceCents(), c.getImpressionPriceCents(), c.getConsumedCents(),
                c.getBudgetCents() - c.getConsumedCents(), c.getCurrency(),
                c.getStatus().name(), c.getStartsAt(), c.getEndsAt(), c.getBilledThrough(),
                c.getCreatedAt());
    }

    private static AdBillingChargeView toChargeView(AdBillingCharge ch) {
        return new AdBillingChargeView(ch.getWindowStart(), ch.getWindowEnd(),
                ch.getImpressions(), ch.getClicks(), ch.getAmountCents(), ch.getCurrency(),
                ch.getCreatedAt());
    }

    /** The campaign's owner-facing truth — the frozen money state included. */
    public record AdCampaignView(
            UUID id,
            UUID listingId,
            long budgetCents,
            long clickPriceCents,
            long impressionPriceCents,
            long consumedCents,
            long remainingCents,
            String currency,
            String status,
            Instant startsAt,
            Instant endsAt,
            LocalDate billedThrough,
            Instant createdAt
    ) {
    }

    /** One frozen window of the immutable billing history. */
    public record AdBillingChargeView(
            LocalDate windowStart,
            LocalDate windowEnd,
            long impressions,
            long clicks,
            long amountCents,
            String currency,
            Instant frozenAt
    ) {
    }

    /**
     * The creation request — bean-validated at the controller: the budget
     * positive (a zero-budget campaign is the no-campaign), the prices
     * non-negative (a zero price honestly makes that event free), the
     * currency a valid ISO 4217 code (blank keeps the house default).
     */
    public record CreateAdCampaignRequest(
            @jakarta.validation.constraints.NotNull UUID listingId,
            @jakarta.validation.constraints.Positive long budgetCents,
            @jakarta.validation.constraints.PositiveOrZero long clickPriceCents,
            @jakarta.validation.constraints.PositiveOrZero long impressionPriceCents,
            String currency,
            Instant endsAt
    ) {
    }
}
