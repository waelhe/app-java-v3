package com.marketplace.provider;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewStats;
import com.marketplace.shared.api.ReviewStatsPort;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
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

    private final ProviderPublicPageService service =
            new ProviderPublicPageService(providerService, catalogSearchPort, reviewStatsPort);

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
        when(catalogSearchPort.listActiveByProvider(eq(userId), eq(pageable))).thenReturn(pageOf(2));
        // The rating aggregates in the REVIEWS' id space (users.id — the V6
        // FK's space, the class javadoc's measured fact).
        when(reviewStatsPort.findStatsByProviderId(userId))
                .thenReturn(Optional.of(new ReviewStats(providerId, 4.5, 12)));

        var result = service.getPublicPage(providerId, pageable);

        assertThat(result.status()).isEqualTo(ProviderStatus.VERIFIED);
        assertThat(result.actorType()).isEqualTo(ProviderActorType.INDEPENDENT_BROKER);
        assertThat(result.agencyName()).isEqualTo("Qudsia Prime");
        assertThat(result.licenseNumber()).isEqualTo("BR-2026-1149");
        assertThat(result.ratingAverage()).isEqualTo(4.5);
        assertThat(result.reviewCount()).isEqualTo(12L);
        assertThat(result.listings().totalElements()).isEqualTo(2);
        verify(catalogSearchPort).listActiveByProvider(userId, pageable);
    }

    @Test
    void suspendedProvider_hidesTheListingsBlock() {
        UUID providerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.SUSPENDED, userId));
        when(reviewStatsPort.findStatsByProviderId(providerId)).thenReturn(Optional.empty());

        var result = service.getPublicPage(providerId, pageable);

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

        var result = service.getPublicPage(providerId, pageable);

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

        var result = service.getPublicPage(providerId, pageable);

        assertThat(result.listings().totalElements()).isZero();
        verifyNoInteractions(catalogSearchPort);
    }

    @Test
    void noReviews_nullAverageZeroCount() {
        UUID providerId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        when(providerService.getById(providerId)).thenReturn(profile(ProviderStatus.VERIFIED, UUID.randomUUID()));
        when(catalogSearchPort.listActiveByProvider(any(), eq(pageable))).thenReturn(Page.empty(pageable));
        when(reviewStatsPort.findStatsByProviderId(any())).thenReturn(Optional.empty());

        var result = service.getPublicPage(providerId, pageable);

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

        var result = service.getPublicPage(providerId, pageable);

        assertThat(result.ratingAverage()).isNull();
        assertThat(result.reviewCount()).isZero();
        assertThat(result.listings().totalElements()).isZero();
        verifyNoInteractions(catalogSearchPort, reviewStatsPort);
    }

    @Test
    void unknownProvider_propagatesThe404() {
        UUID providerId = UUID.randomUUID();
        when(providerService.getById(providerId)).thenThrow(new ResourceNotFoundException("Provider not found"));

        assertThatThrownBy(() -> service.getPublicPage(providerId, PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(catalogSearchPort, reviewStatsPort);
    }

    private static Page<ListingSummary> pageOf(int count) {
        List<ListingSummary> content = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new ListingSummary(UUID.randomUUID(), "Listing " + i, "APARTMENT",
                        BigDecimal.valueOf(1000 + i), "SAR", "Qudsia Prime"))
                .toList();
        return new PageImpl<>(content, PageRequest.of(0, 20), count);
    }
}
