package com.marketplace.provider;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.RatingDistribution;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewMode;
import com.marketplace.shared.api.ReviewStats;
import com.marketplace.shared.api.ReviewStatsPort;
import com.marketplace.shared.api.SystemSettingKeys;
import com.marketplace.shared.api.PublishedReviewView;
import com.marketplace.shared.api.PublishedReviewsPort;
import com.marketplace.shared.api.SystemSettingsPort;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.Mockito.*;

/**
 * L36 (realestate systems plan §5): the public page assembly — the VERIFIED
 * gate on the listings block (acceptance criterion 2), the fresh rating
 * snapshot, and the honest empty shapes.
 */
class ProviderPublicPageServiceTest {

    private final ProviderService providerService = mock(ProviderService.class);
    private final CatalogSearchPort catalogSearchPort = mock(CatalogSearchPort.class);
    private final ReviewStatsPort reviewStatsPort = mock(ReviewStatsPort.class);
    private final SystemSettingsPort systemSettingsPort = mock(SystemSettingsPort.class);
    private final PublishedReviewsPort publishedReviewsPort = mock(PublishedReviewsPort.class);
    private final ProviderBusinessPageService businessPageService = mock(ProviderBusinessPageService.class);
    private final GeoLookupPort geoLookupPort = mock(GeoLookupPort.class);
    private final ProviderProperties providerProperties = new ProviderProperties(
            new ProviderProperties.Seo("", "/providers/{id}"));

    private final ProviderPublicPageService service = publicPageService(ReviewMode.VERIFIED_ONLY);

    /**
     * W1 §4.4: a fresh service per tested mode (re-stubbing the shared
     * {@code reviews.mode} answer); the default instance is the seeded mode
     * so every pre-W1 test runs the old composition byte for byte.
     */
    private ProviderPublicPageService publicPageService(ReviewMode mode) {
        when(systemSettingsPort.getStringOrDefault(
                eq(SystemSettingKeys.REVIEWS_MODE), anyString())).thenReturn(mode.name());
        // W1: the reviews block's port — the honest empty page unless a
        // test stubs real rows (the neutral request answered empty).
        when(publishedReviewsPort.findPublishedByProviderUserId(any(UUID.class), any(PagedRequest.class)))
                .thenAnswer(invocation -> PagedResponse.empty(invocation.getArgument(1, PagedRequest.class)));
        // W2: the business-page blocks' neutral stubs — an honest empty
        // week/menu/areas and the all-zero verified histogram (the complete
        // five-bucket contract the port itself guarantees).
        when(businessPageService.getHours(any(UUID.class))).thenReturn(List.of());
        when(businessPageService.getServices(any(UUID.class))).thenReturn(List.of());
        when(businessPageService.getAreas(any(UUID.class))).thenReturn(List.of());
        when(geoLookupPort.getTree()).thenReturn(new GeoLookupPort.GeoNode(
                UUID.randomUUID(), null, 0, "الجذر", "root", "root", List.of()));
        when(reviewStatsPort.findRatingDistributionByProviderId(any(UUID.class)))
                .thenReturn(RatingDistribution.of(UUID.randomUUID(), List.of()));
        when(reviewStatsPort.findGeneralRatingDistributionByProviderId(any(UUID.class)))
                .thenReturn(RatingDistribution.of(UUID.randomUUID(), List.of()));
        return new ProviderPublicPageService(providerService, catalogSearchPort,
                reviewStatsPort, systemSettingsPort, publishedReviewsPort,
                businessPageService, geoLookupPort, providerProperties);
    }

    private static ProviderProfile profile(ProviderStatus status, UUID userId) {
        return Instancio.of(ProviderProfile.class)
                .set(field(ProviderProfile::getStatus), status)
                .set(field(ProviderProfile::getUserId), userId)
                .set(field(ProviderProfile::getActorType), ProviderActorType.INDEPENDENT_BROKER)
                .set(field(ProviderProfile::getAgencyName), "Qudsia Prime")
                .set(field(ProviderProfile::getLicenseNumber), "BR-2026-1149")
                .create();
    }

    @Test
    void verifiedProvider_servesListingsThroughTheCatalogPort() {
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.VERIFIED, userId));
        when(catalogSearchPort.listActiveByProvider(eq(userId), eq(PagedRequest.of(0, 20)))).thenReturn(pageOf(2));
        // The rating aggregates in the REVIEWS' id space (users.id — the V6
        // FK's space, the class javadoc's measured fact).
        when(reviewStatsPort.findStatsByProviderId(userId))
                .thenReturn(Optional.of(new ReviewStats(providerId, 4.5, 12)));

        var result = service.getPublicPage(providerId, pageable, pageable);

        assertThat(result.status()).isEqualTo(ProviderStatus.VERIFIED);
        assertThat(result.actorType()).isEqualTo(ProviderActorType.INDEPENDENT_BROKER);
        assertThat(result.agencyName()).isEqualTo("Qudsia Prime");
        assertThat(result.licenseNumber()).isEqualTo("BR-2026-1149");
        assertThat(result.ratingAverage()).isEqualTo(4.5);
        assertThat(result.reviewCount()).isEqualTo(12L);
        assertThat(result.ratingGeneralAverage())
                .as("the seeded mode shows no second badge")
                .isNull();
        assertThat(result.ratingGeneralCount()).isZero();
        assertThat(result.listings().totalElements()).isEqualTo(2);
        verify(catalogSearchPort).listActiveByProvider(userId, PagedRequest.of(0, 20));
    }

    /**
     * W1 §4.4 — HYBRID displays the two badges separately (the plan's
     * «موثّق 4.8 (23) · عام 4.2 (156)»).
     */
    @Test
    void hybridMode_showsBothBadgesSeparately() {
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.VERIFIED, userId));
        when(catalogSearchPort.listActiveByProvider(eq(userId), eq(PagedRequest.of(0, 20)))).thenReturn(pageOf(2));
        when(reviewStatsPort.findStatsByProviderId(userId))
                .thenReturn(Optional.of(new ReviewStats(userId, 4.8, 23)));
        when(reviewStatsPort.findGeneralStatsByProviderId(userId))
                .thenReturn(Optional.of(new ReviewStats(userId, 4.2, 156)));

        var result = publicPageService(ReviewMode.HYBRID).getPublicPage(providerId, pageable, pageable);

        assertThat(result.ratingAverage()).isEqualTo(4.8);
        assertThat(result.reviewCount()).isEqualTo(23L);
        assertThat(result.ratingGeneralAverage()).isEqualTo(4.2);
        assertThat(result.ratingGeneralCount()).isEqualTo(156L);
    }

    /**
     * W1 §4.4 — OPEN merges the two aggregates into one count-weighted
     * number («يُدمج المجموعان في رقم واحد»), no second badge: (4.8×23 +
     * 4.2×156) / 179.
     */
    @Test
    void openMode_mergesBothOriginsIntoOneNumber() {
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.VERIFIED, userId));
        when(catalogSearchPort.listActiveByProvider(eq(userId), eq(PagedRequest.of(0, 20)))).thenReturn(pageOf(2));
        when(reviewStatsPort.findStatsByProviderId(userId))
                .thenReturn(Optional.of(new ReviewStats(userId, 4.8, 23)));
        when(reviewStatsPort.findGeneralStatsByProviderId(userId))
                .thenReturn(Optional.of(new ReviewStats(userId, 4.2, 156)));

        var result = publicPageService(ReviewMode.OPEN).getPublicPage(providerId, pageable, pageable);

        assertThat(result.ratingAverage()).isEqualTo((4.8 * 23 + 4.2 * 156) / 179.0);
        assertThat(result.reviewCount()).isEqualTo(179L);
        assertThat(result.ratingGeneralAverage()).isNull();
        assertThat(result.ratingGeneralCount()).isZero();
    }

    @Test
    void suspendedProvider_hidesTheListingsBlock() {
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.SUSPENDED, userId));
        when(reviewStatsPort.findStatsByProviderId(providerId)).thenReturn(Optional.empty());

        var result = service.getPublicPage(providerId, pageable, pageable);

        assertThat(result.status()).isEqualTo(ProviderStatus.SUSPENDED);
        assertThat(result.listings().totalElements()).isZero();
        assertThat(result.listings().pageNumber()).isEqualTo(0);
        assertThat(result.listings().pageSize()).isEqualTo(20);
        verifyNoInteractions(catalogSearchPort);
    }

    @Test
    void pendingProvider_hidesTheListingsBlock() {
        UUID providerId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.PENDING, UUID.randomUUID()));
        when(reviewStatsPort.findStatsByProviderId(providerId)).thenReturn(Optional.empty());

        var result = service.getPublicPage(providerId, pageable, pageable);

        assertThat(result.listings().totalElements()).isZero();
        verifyNoInteractions(catalogSearchPort);
    }

    @Test
    void profileWithoutUserId_hidesTheListingsBlock() {
        // The A1 seam: a profile with no linked user id cannot own listings
        // (provider_listings.provider_id lives in the users.id space).
        UUID providerId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.VERIFIED, null));
        when(reviewStatsPort.findStatsByProviderId(providerId)).thenReturn(Optional.empty());

        var result = service.getPublicPage(providerId, pageable, pageable);

        assertThat(result.listings().totalElements()).isZero();
        verifyNoInteractions(catalogSearchPort);
    }

    @Test
    void noReviews_nullAverageZeroCount() {
        UUID providerId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.VERIFIED, UUID.randomUUID()));
        when(catalogSearchPort.listActiveByProvider(any(), eq(PagedRequest.of(0, 20))))
                .thenReturn(PagedResponse.empty(PagedRequest.of(0, 20)));
        when(reviewStatsPort.findStatsByProviderId(any())).thenReturn(Optional.empty());

        var result = service.getPublicPage(providerId, pageable, pageable);

        assertThat(result.ratingAverage()).isNull();
        assertThat(result.reviewCount()).isZero();
    }

    @Test
    void profileWithoutUserId_noRatingEither() {
        // The reviews' provider_id lives in the users.id space — a profile
        // with no linked user can own no reviews: the null guard skips the
        // port call entirely (no aggregate by the profile id, ever).
        UUID providerId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.VERIFIED, null));

        var result = service.getPublicPage(providerId, pageable, pageable);

        assertThat(result.ratingAverage()).isNull();
        assertThat(result.reviewCount()).isZero();
        assertThat(result.listings().totalElements()).isZero();
        assertThat(result.reviews().totalElements()).isZero();
        verifyNoInteractions(catalogSearchPort, reviewStatsPort, publishedReviewsPort);
    }

    /**
     * W1 (§4.4/§4.5): the reviews block rides the page through the shared
     * port with the provider's USER id (never the profile id), the mode
     * is declared on the response, and the block is served for a
     * non-VERIFIED provider too (reviews are the reviewed party's public
     * record, not inventory).
     */
    @Test
    void reviewsBlock_ridesThePortWithTheUserId_andTheModeIsDeclared() {
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable listingsPageable = PageRequest.of(0, 20);
        Pageable reviewsPageable = PageRequest.of(0, 10);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.SUSPENDED, userId));
        PublishedReviewView row = new PublishedReviewView(UUID.randomUUID(), 5, "Sourdough sells out by noon",
                "Thank you", null, java.time.Instant.parse("2026-09-20T00:00:00Z"), "ORGANIC", "Nour", 7L, 3L);
        when(publishedReviewsPort.findPublishedByProviderUserId(eq(userId), any(PagedRequest.class)))
                .thenReturn(PagedResponse.of(new PageImpl<>(List.of(row), reviewsPageable, 1)));

        var result = service.getPublicPage(providerId, listingsPageable, reviewsPageable);

        assertThat(result.reviewsMode()).isEqualTo("VERIFIED_ONLY");
        assertThat(result.reviews().totalElements()).isEqualTo(1);
        assertThat(result.reviews().content().getFirst().reviewerName()).isEqualTo("Nour");
        assertThat(result.reviews().content().getFirst().helpfulCount()).isEqualTo(3);
        // the block is NOT VERIFIED-gated: a suspended provider's reviews
        // stay public while his inventory is hidden (the listings gate).
        assertThat(result.listings().totalElements()).isZero();
    }

    @Test
    void unknownProvider_propagatesThe404() {
        UUID providerId = UUID.randomUUID();
        when(providerService.getById(providerId)).thenThrow(new ResourceNotFoundException("Provider not found"));

        assertThatThrownBy(() -> service.getPublicPage(providerId, PageRequest.of(0, 20), PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(catalogSearchPort, reviewStatsPort);
    }

    /**
     * W2 (greptile round, adopted from the root): the JSON-LD review sample
     * follows the population of the aggregate it sits beside. In
     * VERIFIED_ONLY the AggregateRating IS the verified pair — an ORGANIC
     * row (a mode-switch leftover the visible page honestly still lists)
     * must not enter the structured sample. The visible block keeps it.
     */
    @Test
    void verifiedOnlyMode_jsonLdSampleCarriesOnlyBookingRows() {
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.VERIFIED, userId));
        when(catalogSearchPort.listActiveByProvider(any(), eq(PagedRequest.of(0, 20))))
                .thenReturn(PagedResponse.empty(PagedRequest.of(0, 20)));
        when(reviewStatsPort.findStatsByProviderId(userId))
                .thenReturn(Optional.of(new ReviewStats(userId, 4.5, 1)));
        PublishedReviewView organic = new PublishedReviewView(UUID.randomUUID(), 1, "سيء",
                null, null, java.time.Instant.parse("2026-09-01T00:00:00Z"),
                PublishedReviewView.ORIGIN_ORGANIC, "زائر", 2L, 0L);
        PublishedReviewView booking = new PublishedReviewView(UUID.randomUUID(), 5, "ممتاز",
                null, null, java.time.Instant.parse("2026-09-02T00:00:00Z"),
                PublishedReviewView.ORIGIN_BOOKING, "نور", 7L, 3L);
        when(publishedReviewsPort.findPublishedByProviderUserId(eq(userId), any(PagedRequest.class)))
                .thenReturn(PagedResponse.of(new PageImpl<>(List.of(organic, booking), PageRequest.of(0, 10), 2)));

        var result = service.getPublicPage(providerId, pageable, pageable);

        // The visible block carries both rows (the plan's law: the mode
        // governs creation, existing reviews stay listed)...
        assertThat(result.reviews().totalElements()).isEqualTo(2);
        // ...but the structured sample carries only the population the
        // aggregate counts (aggregateRating = the verified pair here).
        assertThat(result.jsonLd().aggregateRating().reviewCount()).isEqualTo(1);
        assertThat(result.jsonLd().review()).hasSize(1);
        assertThat(result.jsonLd().review().getFirst().author().name()).isEqualTo("نور");
    }

    /**
     * W2 (greptile round, adopted from the root): OPEN's aggregate is the
     * merged pair, so both origins enter the structured sample.
     */
    @Test
    void openMode_jsonLdSampleCarriesBothOrigins() {
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.VERIFIED, userId));
        when(catalogSearchPort.listActiveByProvider(any(), eq(PagedRequest.of(0, 20))))
                .thenReturn(PagedResponse.empty(PagedRequest.of(0, 20)));
        when(reviewStatsPort.findStatsByProviderId(userId))
                .thenReturn(Optional.of(new ReviewStats(userId, 4.5, 1)));
        when(reviewStatsPort.findGeneralStatsByProviderId(userId))
                .thenReturn(Optional.of(new ReviewStats(userId, 4.0, 1)));

        // Construct the mode-specific service FIRST (its neutral stubs must
        // not overwrite the row the test then declares — Mockito's
        // last-registered stub wins).
        ProviderPublicPageService openService = publicPageService(ReviewMode.OPEN);
        PublishedReviewView organic = new PublishedReviewView(UUID.randomUUID(), 4, "جيد",
                null, null, java.time.Instant.parse("2026-09-01T00:00:00Z"),
                PublishedReviewView.ORIGIN_ORGANIC, "زائر", 2L, 0L);
        when(publishedReviewsPort.findPublishedByProviderUserId(eq(userId), any(PagedRequest.class)))
                .thenReturn(PagedResponse.of(new PageImpl<>(List.of(organic), PageRequest.of(0, 10), 1)));

        var result = openService.getPublicPage(providerId, pageable, pageable);

        assertThat(result.jsonLd().aggregateRating().reviewCount()).isEqualTo(2);
        assertThat(result.jsonLd().review()).hasSize(1);
    }

    /**
     * W2 (greptile round, adopted from the root): an area whose geo node is
     * soft-deleted after the declaration (the FK honest, the live tree
     * nameless) never enters the structured data — a Place without a usable
     * name is invalid markup. The visible page keeps the honest id row.
     */
    @Test
    void unresolvedArea_isOmittedFromTheJsonLdButKeptOnTheVisiblePage() {
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.VERIFIED, userId));
        when(catalogSearchPort.listActiveByProvider(any(), eq(PagedRequest.of(0, 20))))
                .thenReturn(PagedResponse.empty(PagedRequest.of(0, 20)));
        when(reviewStatsPort.findStatsByProviderId(userId)).thenReturn(Optional.empty());

        UUID resolvedLocation = UUID.randomUUID();
        UUID staleLocation = UUID.randomUUID();
        ServiceArea resolvedArea = ServiceArea.create(providerId, resolvedLocation);
        ServiceArea staleArea = ServiceArea.create(providerId, staleLocation);
        when(businessPageService.getAreas(providerId)).thenReturn(List.of(resolvedArea, staleArea));
        // The live tree carries the resolved node alone (the stale node was
        // soft-deleted after the declaration).
        when(geoLookupPort.getTree()).thenReturn(new GeoLookupPort.GeoNode(
                UUID.randomUUID(), null, 0, "الجذر", "root", "root",
                List.of(new GeoLookupPort.GeoNode(resolvedLocation, null, 1,
                        "قدسيا", "qudsya", "qudsya", List.of()))));

        var result = service.getPublicPage(providerId, pageable, pageable);

        // The visible page keeps BOTH rows (the honest id row, the L39 rule)...
        assertThat(result.serviceAreas()).hasSize(2);
        assertThat(result.serviceAreas().get(1).nameAr()).isNull();
        // ...but the structured data carries only the resolvable Place.
        assertThat(result.jsonLd().areaServed()).hasSize(1);
        assertThat(result.jsonLd().areaServed().getFirst().name()).isEqualTo("قدسيا");
    }

    private static PagedResponse<ListingSummary> pageOf(int count) {
        List<ListingSummary> content = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new ListingSummary(UUID.randomUUID(), "Listing " + i, "APARTMENT",
                        BigDecimal.valueOf(1000 + i), "SAR", "Qudsia Prime"))
                .toList();
        return PagedResponse.of(new PageImpl<>(content, PageRequest.of(0, 20), count));
    }
}
