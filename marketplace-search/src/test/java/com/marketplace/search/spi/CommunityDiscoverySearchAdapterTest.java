package com.marketplace.search.spi;

import com.marketplace.shared.api.CommunityDiscoveryPort;
import com.marketplace.shared.api.DiscoveryCardView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.shared.api.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5's community-leg contract, measured (§5.1): the hydration-time
 * eligibility re-check, the exact-scope law (an absent location is an
 * honest empty leg — never a national widening), the observable safe
 * failure (a dark source is degraded, never fabricated) and the
 * (sourceType, sourceId) dedup — each in its smallest honest unit.
 */
class CommunityDiscoverySearchAdapterTest {

    private static final UUID SCOPE = UUID.randomUUID();
    private static final PagedRequest PAGE = PagedRequest.of(0, 20);

    private CommunityDiscoveryPort port;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CommunityDiscoveryPort> provider =
            (ObjectProvider<CommunityDiscoveryPort>) Mockito.mock(ObjectProvider.class);
    private CommunityDiscoverySearchAdapter adapter;

    @BeforeEach
    void wire() {
        port = mock(CommunityDiscoveryPort.class);
        when(provider.getIfAvailable()).thenReturn(port);
        adapter = new CommunityDiscoverySearchAdapter(provider);
        when(port.findActiveLostFound(any(), any()))
                .thenReturn(PagedResponse.of(org.springframework.data.domain.Page.empty()));
        when(port.findRecommendations(any(), any()))
                .thenReturn(PagedResponse.of(org.springframework.data.domain.Page.empty()));
        when(port.findUpcomingEvents(any(), any()))
                .thenReturn(PagedResponse.of(org.springframework.data.domain.Page.empty()));
    }

    @Test
    void absentScopeIsAnHonestEmptyLegAndNeverTouchesTheSource() {
        SearchCriteria criteria = criteria(null);

        CommunityDiscoverySearchAdapter.CommunityLegAnswer answer = adapter.search(criteria, PAGE);

        assertThat(answer.cards()).isEmpty();
        assertThat(answer.sourceAvailable()).isTrue();
        verify(port, never()).findActiveLostFound(any(), any());
        verify(port, never()).findRecommendations(any(), any());
        verify(port, never()).findUpcomingEvents(any(), any());
    }

    @Test
    void disabledSourceIsADegradedLegLabelledHonestly() {
        when(provider.getIfAvailable()).thenReturn(null);

        CommunityDiscoverySearchAdapter.CommunityLegAnswer answer =
                adapter.search(criteria(SCOPE), PAGE);

        assertThat(answer.cards()).isEmpty();
        assertThat(answer.sourceAvailable()).isFalse();
    }

    @Test
    void unavailableSourceDegradesTheLegAndNeverFabricates() {
        when(port.findActiveLostFound(any(), any()))
                .thenThrow(new ServiceUnavailableException("community source down"));

        CommunityDiscoverySearchAdapter.CommunityLegAnswer answer =
                adapter.search(criteria(SCOPE), PAGE);

        assertThat(answer.cards()).isEmpty();
        assertThat(answer.sourceAvailable()).isFalse();
    }

    @Test
    void eligibilityReCheckDropsInvisibleWithdrawnAndForeignCards() {
        UUID postId = UUID.randomUUID();
        CommunityDiscoveryPort.DiscoveryPostCard visible =
                post(postId, "VISIBLE", null, SCOPE);
        CommunityDiscoveryPort.DiscoveryPostCard moderated =
                post(UUID.randomUUID(), "HIDDEN", null, SCOPE);
        CommunityDiscoveryPort.DiscoveryPostCard resolvedReport =
                post(UUID.randomUUID(), "VISIBLE", "RESOLVED", SCOPE);
        CommunityDiscoveryPort.DiscoveryPostCard foreign =
                post(UUID.randomUUID(), "VISIBLE", null, UUID.randomUUID());
        when(port.findActiveLostFound(any(), any()))
                .thenReturn(PagedResponse.of(new org.springframework.data.domain.PageImpl<>(
                        List.of(visible, moderated, resolvedReport, foreign))));

        UUID eventId = UUID.randomUUID();
        CommunityDiscoveryPort.DiscoveryEventCard upcoming =
                event(eventId, "ACTIVE", SCOPE);
        CommunityDiscoveryPort.DiscoveryEventCard cancelled =
                event(UUID.randomUUID(), "CANCELLED", SCOPE);
        CommunityDiscoveryPort.DiscoveryEventCard foreignEvent =
                event(UUID.randomUUID(), "ACTIVE", UUID.randomUUID());
        when(port.findUpcomingEvents(any(), any()))
                .thenReturn(PagedResponse.of(new org.springframework.data.domain.PageImpl<>(
                        List.of(upcoming, cancelled, foreignEvent))));

        CommunityDiscoverySearchAdapter.CommunityLegAnswer answer =
                adapter.search(criteria(SCOPE), PAGE);

        // exactly the eligible cards survive, mapped verbatim
        assertThat(answer.cards()).hasSize(2);
        assertThat(answer.cards())
                .extracting(DiscoveryCardView::sourceId)
                .containsExactlyInAnyOrder(postId, eventId);
        assertThat(answer.sourceAvailable()).isTrue();
    }

    @Test
    void firstOccurrenceOfEverySourcePairSurvivesTheDedup() {
        UUID postId = UUID.randomUUID();
        CommunityDiscoveryPort.DiscoveryPostCard lostFound =
                post(postId, "VISIBLE", "ACTIVE", SCOPE);
        CommunityDiscoveryPort.DiscoveryPostCard recommendation =
                post(postId, "VISIBLE", null, SCOPE); // same record, two legs
        when(port.findActiveLostFound(any(), any()))
                .thenReturn(PagedResponse.of(new org.springframework.data.domain.PageImpl<>(List.of(lostFound))));
        when(port.findRecommendations(any(), any()))
                .thenReturn(PagedResponse.of(new org.springframework.data.domain.PageImpl<>(List.of(recommendation))));

        CommunityDiscoverySearchAdapter.CommunityLegAnswer answer =
                adapter.search(criteria(SCOPE), PAGE);

        assertThat(answer.cards()).hasSize(1);
        assertThat(answer.cards().getFirst().sourceType())
                .isEqualTo(CommunityDiscoverySearchAdapter.SOURCE_NEIGHBORHOOD_POST);
    }

    @Test
    void mappingCarriesTheSourceFactsVerbatim() {
        Instant stamp = Instant.parse("2026-10-10T10:15:00Z");
        UUID postId = UUID.randomUUID();
        CommunityDiscoveryPort.DiscoveryPostCard lostFound =
                new CommunityDiscoveryPort.DiscoveryPostCard(postId, UUID.randomUUID(),
                        "LOST_FOUND", "ACTIVE", "قطة مفقودة", "آخر ظهور قرب المدرسة",
                        "VISIBLE", SCOPE, stamp);
        when(port.findActiveLostFound(any(), any()))
                .thenReturn(PagedResponse.of(new org.springframework.data.domain.PageImpl<>(List.of(lostFound))));

        CommunityDiscoverySearchAdapter.CommunityLegAnswer answer =
                adapter.search(criteria(SCOPE), PAGE);

        DiscoveryCardView card = answer.cards().getFirst();
        assertThat(card.sourceType()).isEqualTo(CommunityDiscoverySearchAdapter.SOURCE_NEIGHBORHOOD_POST);
        assertThat(card.sourceId()).isEqualTo(postId);
        assertThat(card.updatedAt()).isEqualTo(stamp);
        assertThat(card.title()).isEqualTo("قطة مفقودة");
        assertThat(card.snippet()).isEqualTo("آخر ظهور قرب المدرسة");
        assertThat(card.state()).isEqualTo("ACTIVE");
        assertThat(card.scopeLocationId()).isEqualTo(SCOPE);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private SearchCriteria criteria(UUID locationId) {
        return new SearchCriteria(null, null, null, null, null, null, null,
                locationId, null, null, null, null, null, null, null, null, null);
    }

    private CommunityDiscoveryPort.DiscoveryPostCard post(UUID id, String status,
                                                          String lostFoundState, UUID locationId) {
        return new CommunityDiscoveryPort.DiscoveryPostCard(id, UUID.randomUUID(),
                "GENERAL", lostFoundState, "عنوان", "متن", status, locationId,
                Instant.parse("2026-10-10T09:00:00Z"));
    }

    private CommunityDiscoveryPort.DiscoveryEventCard event(UUID id, String status, UUID locationId) {
        return new CommunityDiscoveryPort.DiscoveryEventCard(id, locationId, "فعالية",
                "وصف", status, "حي", Instant.parse("2026-10-20T18:00:00Z"),
                Instant.parse("2026-10-20T21:00:00Z"), Instant.parse("2026-10-10T09:00:00Z"));
    }
}
