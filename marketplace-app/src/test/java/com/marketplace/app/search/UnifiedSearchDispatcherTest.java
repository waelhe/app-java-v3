package com.marketplace.app.search;

import com.marketplace.community.spi.CommunityPostSearchPort;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.shared.api.UnifiedSearchDomain;
import com.marketplace.shared.api.UnifiedSearchHit;
import com.marketplace.shared.api.MarketplaceSearchPort;
import com.marketplace.shared.api.ListingSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * §5.1 — the unified dispatch slice: the routing map, the common-subset
 * criteria (text + location node ONLY — no giant criteria object), the
 * honest hit provenance card, and the community leg's authenticated-caller
 * contract. The dispatcher holds ports, not repositories — the eligibility
 * lives in the domain paths (asserted here as: the dispatcher never touches
 * anything but the two ports).
 */
@ExtendWith(MockitoExtension.class)
class UnifiedSearchDispatcherTest {

    @Mock
    private MarketplaceSearchPort marketplaceSearchPort;

    @Mock
    private CommunityPostSearchPort communityPostSearchPort;

    @InjectMocks
    private UnifiedSearchDispatcher dispatcher;

    private static final UUID CALLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    @Test
    void listings_ridesTheStandingOrchestrationWithTheCommonSubsetCriteria() {
        ListingSummary listing = new ListingSummary(UUID.randomUUID(), "Oven — like new",
                "APPLIANCES", new BigDecimal("250.00"), "SAR", "Abdullah", 4.6, 31);
        PagedRequest request = PagedRequest.of(0, 20);
        when(marketplaceSearchPort.search(any(SearchCriteria.class), eq(request)))
                .thenReturn(PagedResponse.of(new org.springframework.data.domain.PageImpl<>(List.of(listing))));

        PagedResponse<UnifiedSearchHit> result =
                dispatcher.search(CALLER_ID, UnifiedSearchDomain.LISTINGS, "oven", LOCATION_ID, request);

        // The criteria carry the common subset ONLY: text + the location
        // node; every facet stays null (no giant criteria object).
        ArgumentCaptor<SearchCriteria> criteria = ArgumentCaptor.forClass(SearchCriteria.class);
        verify(marketplaceSearchPort).search(criteria.capture(), eq(request));
        assertThat(criteria.getValue().query()).isEqualTo("oven");
        assertThat(criteria.getValue().locationId()).isEqualTo(LOCATION_ID);
        assertThat(criteria.getValue().purpose()).isNull();
        assertThat(criteria.getValue().minPrice()).isNull();
        assertThat(criteria.getValue().checkIn()).isNull();

        // The honest provenance card: source, domain, the original id.
        assertThat(result.content()).hasSize(1);
        UnifiedSearchHit hit = result.content().get(0);
        assertThat(hit.id()).isEqualTo(listing.id());
        assertThat(hit.domain()).isEqualTo(UnifiedSearchDomain.LISTINGS);
        assertThat(hit.source()).isEqualTo("listing");
        assertThat(hit.title()).isEqualTo("Oven — like new");
        verifyNoInteractions(communityPostSearchPort);
    }

    @Test
    void communityPosts_dispatchesThroughTheSpiWithTheCallerScope() {
        PagedRequest request = PagedRequest.of(0, 20);
        UnifiedSearchHit hit = new UnifiedSearchHit(UUID.randomUUID(),
                UnifiedSearchDomain.COMMUNITY_POSTS, "community_post",
                "Lost keys", "I lost my keys near the mosque", "PUBLISHED",
                java.time.Instant.now(), LOCATION_ID);
        when(communityPostSearchPort.search(CALLER_ID, "مفتاح", request))
                .thenReturn(new PagedResponse<>(List.of(hit), 0, 20, 1, 1, true));

        PagedResponse<UnifiedSearchHit> result =
                dispatcher.search(CALLER_ID, UnifiedSearchDomain.COMMUNITY_POSTS, "مفتاح", null, request);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).source()).isEqualTo("community_post");
        verifyNoInteractions(marketplaceSearchPort);
    }

    @Test
    void communityPosts_withoutACaller_isRejectedBeforeAnyDispatch() {
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> dispatcher.search(null, UnifiedSearchDomain.COMMUNITY_POSTS,
                        "مفتاح", null, PagedRequest.of(0, 20)));
        // No dispatch happened — the membership scope cannot be bypassed.
        verifyNoInteractions(communityPostSearchPort);
        verifyNoInteractions(marketplaceSearchPort);
    }
}
