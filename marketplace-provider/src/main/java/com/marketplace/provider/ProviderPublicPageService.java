package com.marketplace.provider;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PublishedReviewView;
import com.marketplace.shared.api.PublishedReviewsPort;
import com.marketplace.shared.api.RatingDistribution;
import com.marketplace.shared.api.ReviewMode;
import com.marketplace.shared.api.SpringPagination;import com.marketplace.shared.api.ReviewStats;
import com.marketplace.shared.api.ReviewStatsPort;
import com.marketplace.shared.api.SystemSettingKeys;
import com.marketplace.shared.api.SystemSettingsPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final SystemSettingsPort systemSettingsPort;
    private final PublishedReviewsPort publishedReviewsPort;
    private final ProviderBusinessPageService businessPageService;
    private final GeoLookupPort geoLookupPort;
    private final ProviderProperties providerProperties;

    public ProviderPublicPageService(ProviderService providerService,
                                     CatalogSearchPort catalogSearchPort,
                                     ReviewStatsPort reviewStatsPort,
                                     SystemSettingsPort systemSettingsPort,
                                     PublishedReviewsPort publishedReviewsPort,
                                     ProviderBusinessPageService businessPageService,
                                     GeoLookupPort geoLookupPort,
                                     ProviderProperties providerProperties) {
        this.providerService = providerService;
        this.catalogSearchPort = catalogSearchPort;
        this.reviewStatsPort = reviewStatsPort;
        this.systemSettingsPort = systemSettingsPort;
        this.publishedReviewsPort = publishedReviewsPort;
        this.businessPageService = businessPageService;
        this.geoLookupPort = geoLookupPort;
        this.providerProperties = providerProperties;
    }

    public ProviderPublicPageResponse getPublicPage(UUID providerId, Pageable listingsPageable,
                                                       Pageable reviewsPageable) {
        ProviderProfile profile = providerService.getById(providerId);

        Page<ListingSummary> listingsPage = listingsBlock(profile, listingsPageable);
        PagedResponse<ListingSummary> listings = PagedResponse.of(listingsPage);

        Optional<ReviewStats> verified = profile.getUserId() == null
                ? Optional.empty()
                : reviewStatsPort.findStatsByProviderId(profile.getUserId());
        Optional<ReviewStats> general = profile.getUserId() == null
                ? Optional.empty()
                : reviewStatsPort.findGeneralStatsByProviderId(profile.getUserId());

        ReviewMode mode = ReviewMode.parse(systemSettingsPort.getStringOrDefault(
                SystemSettingKeys.REVIEWS_MODE, ReviewMode.VERIFIED_ONLY.name()));

        // The rating block per mode (§4.4) — a switch EXPRESSION, so the
        // exhaustive enum arm set is compiler-checked (definite assignment
        // by construction).
        record RatingBlock(Double ratingAverage, long reviewCount,
                           Double ratingGeneralAverage, long ratingGeneralCount) {
        }
        RatingBlock block = switch (mode) {
            case VERIFIED_ONLY -> {
                // The seed mode: the verified aggregate alone — byte-identical
                // to the pre-W1 page on all-BOOKING data.
                yield new RatingBlock(
                        verified.map(ReviewStats::averageRating).orElse(null),
                        verified.map(ReviewStats::reviewCount).orElse(0L),
                        null, 0L);
            }
            case HYBRID -> {
                // The plan's strongest trust display: the two badges
                // separately («موثّق 4.8 (23) · عام 4.2 (156)»).
                yield new RatingBlock(
                        verified.map(ReviewStats::averageRating).orElse(null),
                        verified.map(ReviewStats::reviewCount).orElse(0L),
                        general.map(ReviewStats::averageRating).orElse(null),
                        general.map(ReviewStats::reviewCount).orElse(0L));
            }
            case OPEN -> {
                // The plan's «يُدمج المجموعان في رقم واحد»: one count-weighted
                // mean over both origins, no second badge on the response.
                long total = verified.map(ReviewStats::reviewCount).orElse(0L)
                        + general.map(ReviewStats::reviewCount).orElse(0L);
                if (total == 0) {
                    yield new RatingBlock(null, 0L, null, 0L);
                }
                double weighted = verified
                        .map(stats -> stats.averageRating() * stats.reviewCount()).orElse(0.0)
                        + general.map(stats -> stats.averageRating() * stats.reviewCount()).orElse(0.0);
                yield new RatingBlock(weighted / total, total, null, 0L);
            }
        };

        // W1 (§4.4/§4.5): the reviews block — the PUBLISHED forward page of
        // this provider, composed through the shared port (the NEUTRAL
        // contracts: the reviews pageable translates through the documented
        // SpringPagination interop corner before it crosses the boundary).
        // The block is NOT VERIFIED-gated (reviews are the reviewed party's
        // public record, not inventory) and a profile without a linked user
        // id can own no reviews (the same honest empty block as the rating
        // pair).
        PagedRequest reviewsRequest = SpringPagination.toPagedRequest(reviewsPageable);
        PagedResponse<PublishedReviewView> reviews = profile.getUserId() == null
                ? PagedResponse.empty(reviewsRequest)
                : publishedReviewsPort.findPublishedByProviderUserId(profile.getUserId(), reviewsRequest);

        // W2 (§5 — the business page): the «توزيع نجوم» histograms — the
        // same mode law as the badge pair above (VERIFIED_ONLY the verified
        // bars; OPEN the merged bars; HYBRID both), resolved by the user id
        // the stats pair already resolved through. A profile with no linked
        // user id can own no reviews: both histograms stay null (the honest
        // "not yet rated" the aggregate pair itself carries).
        List<RatingDistribution.RatingBucket> distribution = null;
        List<RatingDistribution.RatingBucket> generalDistribution = null;
        if (profile.getUserId() != null) {
            RatingDistribution verifiedBars =
                    reviewStatsPort.findRatingDistributionByProviderId(profile.getUserId());
            RatingDistribution generalBars =
                    reviewStatsPort.findGeneralRatingDistributionByProviderId(profile.getUserId());
            switch (mode) {
                case VERIFIED_ONLY -> distribution = verifiedBars.buckets();
                case OPEN -> distribution = RatingDistribution.merge(verifiedBars, generalBars).buckets();
                case HYBRID -> {
                    distribution = verifiedBars.buckets();
                    generalDistribution = generalBars.buckets();
                }
            }
        }

        // W2 (§5 — the business page): the three declared blocks (G11/G12/G13)
        // — live reads through the module's own business-page service; a
        // provider who declared nothing gets the honest empty lists.
        List<BusinessHour> hours = businessPageService.getHours(providerId);
        List<OfferedService> services = businessPageService.getServices(providerId);
        List<ServiceArea> areas = businessPageService.getAreas(providerId);
        List<ProviderPublicPageResponse.ServiceAreaView> areaViews = resolveAreaNames(areas);

        // W2 (G22): the LocalBusiness structured-data block — composed from
        // the SAME numbers the visible blocks render (a rich result that
        // disagrees with its own page is invalid markup): the mode-driven
        // aggregate pair from the rating block above, the declared hours in
        // the canonical openingHours form, the resolved area names, and the
        // bounded leading sample of the population that aggregate describes.
        //
        // The sample's population follows the aggregate it sits beside
        // (greptile W2 round 2, adopted from the root): in VERIFIED_ONLY and
        // HYBRID the AggregateRating IS the verified pair, so the sample
        // draws from the booking-origin population DIRECTLY — the leading
        // rows, never a filter of the caller's requested page (a page of
        // mode-switch leftover ORGANIC rows would otherwise leave the
        // structured sample empty while the aggregate reports verified
        // reviews). In OPEN the aggregate is the merged pair, so the sample
        // is the leading rows of the same merged population the paged read
        // serves — page 0 by construction, independent of whichever reviews
        // page the caller asked the visible block for.
        List<PublishedReviewView> jsonLdReviews = profile.getUserId() == null ? List.of() : switch (mode) {
            case VERIFIED_ONLY, HYBRID -> publishedReviewsPort.findPublishedSampleByProviderUserIdAndOrigin(
                    profile.getUserId(), PublishedReviewView.ORIGIN_BOOKING, ProviderBusinessJsonLd.MAX_REVIEWS_IN_LD);
            case OPEN -> publishedReviewsPort.findPublishedByProviderUserId(
                    profile.getUserId(),
                    com.marketplace.shared.api.PagedRequest.of(0, ProviderBusinessJsonLd.MAX_REVIEWS_IN_LD)).content();
        };
        ProviderBusinessJsonLd jsonLd = ProviderBusinessJsonLd.of(
                profile,
                hours,
                // Unresolved areas never enter the structured data (greptile
                // W2 round, adopted): a geo node soft-deleted after the
                // declaration leaves the FK honest but the live tree no
                // longer supplies a name — a Place without a usable name is
                // invalid markup, so only resolved names ride; the visible
                // page keeps the honest id row (the L39 rule).
                areaViews.stream()
                        .map(ProviderPublicPageResponse.ServiceAreaView::nameAr)
                        .filter(java.util.Objects::nonNull)
                        .toList(),
                ProviderBusinessJsonLd.aggregateOf(block.ratingAverage(), block.reviewCount()),
                jsonLdReviews,
                providerProperties.seo().providerUrl(profile.getId()));

        return new ProviderPublicPageResponse(
                profile.getId(),
                profile.getDisplayName(),
                profile.getBio(),
                profile.getStatus(),
                profile.getVerificationState(),
                profile.getActorType(),
                profile.getAgencyName(),
                profile.getLicenseNumber(),
                profile.getCreatedAt(),
                mode.name(),
                block.ratingAverage(),
                block.reviewCount(),
                block.ratingGeneralAverage(),
                block.ratingGeneralCount(),
                distribution,
                generalDistribution,
                reviews,
                listings,
                hours.stream().map(ProviderPublicPageResponse.BusinessHourView::of).toList(),
                services.stream().map(ProviderPublicPageResponse.OfferedServiceView::of).toList(),
                areaViews,
                jsonLd);
    }

    /**
     * W2 (G13): the declared areas' display names, resolved through the
     * geo module's CACHED tree — the ListingSeoService pattern verbatim
     * (one {@code getTree()} call, flattened once, per-node lookup; the
     * tree is "small by design — hundreds of rows at city scale"). An
     * area whose node is absent from the tree renders by its id alone
     * (the name fields null — no invented place names, the L39 rule).
     */
    private List<ProviderPublicPageResponse.ServiceAreaView> resolveAreaNames(List<ServiceArea> areas) {
        if (areas.isEmpty()) {
            return List.of();
        }
        Map<UUID, GeoNode> byId = new HashMap<>(64);
        flatten(geoLookupPort.getTree(), byId);
        List<ProviderPublicPageResponse.ServiceAreaView> views = new ArrayList<>(areas.size());
        for (ServiceArea area : areas) {
            GeoNode node = byId.get(area.getLocationId());
            views.add(new ProviderPublicPageResponse.ServiceAreaView(
                    area.getLocationId(),
                    node == null ? null : node.nameAr(),
                    node == null ? null : node.nameEn(),
                    node == null ? null : node.slug()));
        }
        return List.copyOf(views);
    }

    private static void flatten(GeoNode node, Map<UUID, GeoNode> byId) {
        byId.put(node.id(), node);
        for (GeoNode child : node.children()) {
            flatten(child, byId);
        }
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
        return SpringPagination.toPage(
                catalogSearchPort.listActiveByProvider(profile.getUserId(),
                        SpringPagination.toPagedRequest(pageable)),
                pageable);
    }
}
