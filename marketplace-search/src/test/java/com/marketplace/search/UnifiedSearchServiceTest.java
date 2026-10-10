package com.marketplace.search;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.RealestatePropertyFilterPort;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.search.spi.CommunityDiscoverySearchAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The composition contract (§5.1): the unified answer rides the canonical
 * listings orchestration untouched and composes the community leg BESIDE
 * it — per-domain shapes kept honest, the community leg's availability
 * flag surfaced verbatim, and a dark community source never fabricates
 * cards nor disturbs the listings page.
 */
class UnifiedSearchServiceTest {

    private static final PagedRequest PAGE = PagedRequest.of(0, 20);

    private CatalogSearchPort catalogSearchPort;
    private AvailabilityLookupPort availabilityLookupPort;
    private GeoLookupPort geoLookupPort;
    private RealestatePropertyFilterPort realestatePropertyFilterPort;
    private SearchService searchService;
    private MarketplaceSearchAdapter adapter;
    private CommunityDiscoverySearchAdapter communityLeg;
    private UnifiedSearchService service;

    @BeforeEach
    void wire() {
        catalogSearchPort = mock(CatalogSearchPort.class);
        availabilityLookupPort = mock(AvailabilityLookupPort.class);
        geoLookupPort = mock(GeoLookupPort.class);
        realestatePropertyFilterPort = mock(RealestatePropertyFilterPort.class);
        searchService = mock(SearchService.class);
        adapter = new MarketplaceSearchAdapter(searchService);
        communityLeg = mock(CommunityDiscoverySearchAdapter.class);
        service = new UnifiedSearchService(adapter, communityLeg);
    }

    @Test
    void theAnswerComposesBothLegsWithTheirOwnHonestShapes() {
        ListingSummary listing = new ListingSummary(UUID.randomUUID(), "مقهى",
                "SERVICES", BigDecimal.TEN, "SAR", "مزود", 4.5, 3L);
        when(searchService.search(any(SearchCriteria.class), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(listing)));
        com.marketplace.shared.api.DiscoveryCardView card =
                new com.marketplace.shared.api.DiscoveryCardView("NEIGHBORHOOD_POST",
                        UUID.randomUUID(), java.time.Instant.now(), "عنوان", "مقتطف",
                        "VISIBLE", UUID.randomUUID(), null, null);
        when(communityLeg.search(any(SearchCriteria.class), any(PagedRequest.class)))
                .thenReturn(new CommunityDiscoverySearchAdapter.CommunityLegAnswer(
                        List.of(card), true));

        UnifiedSearchService.UnifiedSearchAnswer answer =
                service.search(criteria(UUID.randomUUID()), PAGE);

        assertThat(answer.listings().content()).containsExactly(listing);
        assertThat(answer.communityCards()).containsExactly(card);
        assertThat(answer.communitySourceAvailable()).isTrue();
    }

    @Test
    void aDarkCommunityLegLeavesTheListingsPageIntactAndLabelsTheLeg() {
        ListingSummary listing = new ListingSummary(UUID.randomUUID(), "مقهى",
                "SERVICES", BigDecimal.TEN, "SAR", "مزود", 4.5, 3L);
        when(searchService.search(any(SearchCriteria.class), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(listing)));
        when(communityLeg.search(any(SearchCriteria.class), any(PagedRequest.class)))
                .thenReturn(new CommunityDiscoverySearchAdapter.CommunityLegAnswer(
                        List.of(), false));

        UnifiedSearchService.UnifiedSearchAnswer answer =
                service.search(criteria(null), PAGE);

        assertThat(answer.listings().content()).hasSize(1);
        assertThat(answer.communityCards()).isEmpty();
        assertThat(answer.communitySourceAvailable()).isFalse();
    }

    private SearchCriteria criteria(UUID locationId) {
        return new SearchCriteria(null, null, null, null, null, null, null,
                locationId, null, null, null, null, null, null, null, null, null);
    }
}
