package com.marketplace.provider;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ReviewStats;
import com.marketplace.shared.api.ReviewStatsPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * L36 (realestate systems plan §5 — agent/office pages): the public
 * provider page assembly — profile + ACTIVE listings page + rating block
 * in one read.
 *
 * <p><b>Why a separate service (the ProviderStatsService pattern):</b> the
 * profile block must ride the existing {@code "providers"} cache
 * ({@code ProviderService.getById} is the cached public read), and a
 * self-invocation from within the same class would bypass the cache proxy
 * (the #270 AOP rule). A dedicated read-side service injects the
 * {@code ProviderService} bean and crosses the proxy exactly once — the
 * same structural answer the house applied to the stats concern.
 *
 * <p><b>The VERIFIED gate (acceptance criterion 2):</b> the listings block
 * is served only for VERIFIED profiles — a SUSPENDED broker's inventory is
 * hidden from his public page (an honest empty page, total 0, the
 * pageable's own metadata). PENDING profiles naturally have no listings
 * (creation requires VERIFIED), so the gate reduces to the one status
 * check. The profile itself stays visible with its status — the same
 * public visibility the plain {@code GET /providers/{id}} surface has
 * always had. The global browse/search surfaces keep their existing
 * ACTIVE-only contracts (measured: no provider-status filter exists there
 * — the plan's original "existing behavior" claim is corrected in this
 * layer's truth batch).
 *
 * <p><b>Id spaces (measured, CI-enforced):</b> this service resolves the
 * profile row ({@code provider_profiles.id}) and passes
 * {@code profile.getUserId()} to BOTH cross-module ports — the listings
 * block's {@code provider_listings.provider_id} and the rating block's
 * {@code reviews.provider_id} both live in the <b>users.id</b> space: the
 * V6 FK ({@code references users(id)}) enforces the reviews' space, the
 * production write path writes it ({@code ReviewsService.create} carries
 * {@code bookingInfo.providerId()} = {@code bookings.provider_id} = the
 * owner's user id — the A1 contract), and the FK-honest house test
 * ({@code ReviewsAggregateDirectionIntegrationTest}, Flyway-enabled)
 * seeds and aggregates by the user id. A profile without a linked user id
 * can own neither listings nor reviews: the empty block is the honest
 * answer.
 */
@Service
@Transactional(readOnly = true)
public class ProviderPublicPageService {

    private final ProviderService providerService;
    private final CatalogSearchPort catalogSearchPort;
    private final ReviewStatsPort reviewStatsPort;

    public ProviderPublicPageService(ProviderService providerService,
                                     CatalogSearchPort catalogSearchPort,
                                     ReviewStatsPort reviewStatsPort) {
        this.providerService = providerService;
        this.catalogSearchPort = catalogSearchPort;
        this.reviewStatsPort = reviewStatsPort;
    }

    public ProviderPublicPageResponse getPublicPage(UUID providerId, Pageable pageable) {
        ProviderProfile profile = providerService.getById(providerId);

        Page<ListingSummary> listings = listingsBlock(profile, pageable);

        Optional<ReviewStats> stats = profile.getUserId() == null
                ? Optional.empty()
                : reviewStatsPort.findStatsByProviderId(profile.getUserId());
        Double ratingAverage = stats.map(ReviewStats::averageRating).orElse(null);
        long reviewCount = stats.map(ReviewStats::reviewCount).orElse(0L);

        return new ProviderPublicPageResponse(
                profile.getId(),
                profile.getDisplayName(),
                profile.getBio(),
                profile.getStatus(),
                profile.getActorType(),
                profile.getAgencyName(),
                profile.getLicenseNumber(),
                profile.getCreatedAt(),
                ratingAverage,
                reviewCount,
                PagedResponse.of(listings));
    }

    /**
     * The listings block: served only for VERIFIED profiles with a linked
     * user id (the ownership key of the listings); every other shape gets
     * the honest empty page carrying the caller's own pageable metadata.
     */
    private Page<ListingSummary> listingsBlock(ProviderProfile profile, Pageable pageable) {
        if (profile.getStatus() != ProviderStatus.VERIFIED || profile.getUserId() == null) {
            return Page.empty(pageable);
        }
        return catalogSearchPort.listActiveByProvider(profile.getUserId(), pageable);
    }
}
