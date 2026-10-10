package com.marketplace.community;

import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.UnifiedSearchDomain;
import com.marketplace.shared.api.UnifiedSearchHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * §5.1 — the community side of the unified contract: the adapter maps the
 * measured source (searchFeed — membership-scoped visibility, FTS primary +
 * trigram fallback) into the honest provenance card, passes the unsorted
 * page through (relevance order is the contract), and never adds an axis
 * the domain path does not already enforce.
 */
@ExtendWith(MockitoExtension.class)
class CommunityPostSearchAdapterTest {

    @Mock
    private NeighborhoodPostService postService;

    @InjectMocks
    private CommunityPostSearchAdapter adapter;

    private static final UUID CALLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    @Test
    void search_mapsTheFeedProjectionsToProvenanceHits() {
        NeighborhoodPostView view = new NeighborhoodPostView(
                UUID.randomUUID(), UUID.randomUUID(), LOCATION_ID, "LOST_FOUND",
                "Lost keys", "I lost my keys near the mosque yesterday", "PUBLISHED",
                3L, false, List.of(), Instant.parse("2026-10-10T10:15:00Z"), Instant.parse("2026-10-10T10:15:00Z"));
        when(postService.searchFeed(eq(CALLER_ID), eq("مفتاح"), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(view)));

        PagedResponse<UnifiedSearchHit> result =
                adapter.search(CALLER_ID, "مفتاح", PagedRequest.of(0, 20));

        assertThat(result.content()).hasSize(1);
        UnifiedSearchHit hit = result.content().get(0);
        assertThat(hit.id()).isEqualTo(view.id());
        assertThat(hit.domain()).isEqualTo(UnifiedSearchDomain.COMMUNITY_POSTS);
        assertThat(hit.source()).isEqualTo("community_post");
        assertThat(hit.title()).isEqualTo("Lost keys");
        assertThat(hit.status()).isEqualTo("PUBLISHED");
        assertThat(hit.publishedAt()).isEqualTo(Instant.parse("2026-10-10T10:15:00Z"));
        assertThat(hit.locationId()).isEqualTo(LOCATION_ID);
    }

    @Test
    void search_passesTheUnsortedPageThrough() {
        when(postService.searchFeed(eq(CALLER_ID), eq("سؤال"), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        adapter.search(CALLER_ID, "سؤال", PagedRequest.of(1, 10));

        org.mockito.Mockito.verify(postService).searchFeed(
                eq(CALLER_ID), eq("سؤال"), isNull(),
                eq(PageRequest.of(1, 10)));
    }

    @Test
    void snippet_isBoundedAt200CodePointsWithoutSplittingSurrogates() {
        String emojiTail = "أ".repeat(200) + "🙂🙂";
        String bounded = CommunityPostSearchAdapter.snippet(emojiTail);
        assertThat(bounded.codePointCount(0, bounded.length())).isEqualTo(200);
        // The supplementary character never lands split.
        assertThat(bounded).doesNotContain("\uD83D");

        assertThat(CommunityPostSearchAdapter.snippet(null)).isNull();
        assertThat(CommunityPostSearchAdapter.snippet("short")).isEqualTo("short");
    }
}
