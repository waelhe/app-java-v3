package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 (the first composite journey's feed leg): the neighborhood
 * feed projection's value semantics — the records the home composite's
 * feed read speaks across the module boundary, pinned the
 * {@code DiscoveryContractsTest} way. The tests pin the three
 * guarantees the projection's own contract names:
 * {@link NeighborhoodFeedPostView} defensively copies its media list
 * (no caller can mutate a rendered feed page after the fact — the same
 * guarantee the discovery rails' view carries), the view holds the
 * same-journey field set the REST feed view carries (the #599 gate's
 * comparison contract: nothing narrower, nothing wider), and
 * {@link NeighborhoodFeedMediaView} carries the L48 media projection's
 * own field set (the presigned display pair and the position order —
 * no storage fact).
 */
class NeighborhoodFeedContractsTest {

    private static final Instant T = Instant.parse("2026-10-17T09:00:00Z");

    private static final UUID POST = UUID.nameUUIDFromBytes("post".getBytes());
    private static final UUID AUTHOR = UUID.nameUUIDFromBytes("author".getBytes());
    private static final UUID NEIGHBORHOOD = UUID.nameUUIDFromBytes("neighborhood".getBytes());
    private static final UUID MEDIA = UUID.nameUUIDFromBytes("media".getBytes());

    private static NeighborhoodFeedMediaView media(int position) {
        return new NeighborhoodFeedMediaView(MEDIA, "https://presigned/object", null,
                "image/jpeg", position);
    }

    private static NeighborhoodFeedPostView post(String category, String lostFoundState) {
        return new NeighborhoodFeedPostView(POST, AUTHOR, NEIGHBORHOOD, category,
                "Post title", "The post body", "VISIBLE", false, lostFoundState,
                3L, true, List.of(media(0)), T, T);
    }

    @Test
    void postView_carriesTheSameJourneyFieldSet() {
        NeighborhoodFeedPostView view = post("LOST_FOUND", "ACTIVE");
        assertThat(view.id()).isEqualTo(POST);
        assertThat(view.authorId()).isEqualTo(AUTHOR);
        assertThat(view.locationId()).isEqualTo(NEIGHBORHOOD);
        assertThat(view.category()).isEqualTo("LOST_FOUND");
        assertThat(view.title()).isEqualTo("Post title");
        assertThat(view.body()).isEqualTo("The post body");
        assertThat(view.status()).isEqualTo("VISIBLE");
        assertThat(view.declaredCommercial()).isFalse();
        assertThat(view.lostFoundState()).isEqualTo("ACTIVE");
        assertThat(view.reactionsCount()).isEqualTo(3L);
        assertThat(view.reactedByMe()).isTrue();
        assertThat(view.createdAt()).isEqualTo(T);
        assertThat(view.updatedAt()).isEqualTo(T);
    }

    @Test
    void postView_lostFoundStateRidesOnlyLostFoundPosts() {
        // V171's own shape: every other category carries null — the honest
        // absence, never a fabricated state.
        assertThat(post("GENERAL", null).lostFoundState()).isNull();
        assertThat(post("LOST_FOUND", "RESOLVED").lostFoundState()).isEqualTo("RESOLVED");
    }

    @Test
    void postView_defensivelyCopiesTheMediaList() {
        List<NeighborhoodFeedMediaView> mutable = new ArrayList<>();
        mutable.add(media(0));
        NeighborhoodFeedPostView view = new NeighborhoodFeedPostView(POST, AUTHOR,
                NEIGHBORHOOD, "GENERAL", "t", "b", "VISIBLE", false, null,
                0L, false, mutable, T, T);
        mutable.clear();
        assertThat(view.media()).hasSize(1);
        assertThat(view.media().get(0).position()).isZero();
    }

    @Test
    void postView_recordSemantics_hashEqualsAndToString() {
        NeighborhoodFeedPostView a = post("GENERAL", null);
        NeighborhoodFeedPostView b = post("GENERAL", null);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("GENERAL").contains("Post title");
    }

    @Test
    void mediaView_carriesTheL48ProjectionFieldSet() {
        NeighborhoodFeedMediaView view = new NeighborhoodFeedMediaView(MEDIA,
                "https://presigned/object", "https://presigned/thumb", "image/png", 2);
        assertThat(view.mediaId()).isEqualTo(MEDIA);
        assertThat(view.url()).isEqualTo("https://presigned/object");
        assertThat(view.thumbUrl()).isEqualTo("https://presigned/thumb");
        assertThat(view.contentType()).isEqualTo("image/png");
        assertThat(view.position()).isEqualTo(2);
        // thumbUrl is null until background processing has run — the L28
        // contract the view carries verbatim.
        assertThat(media(0).thumbUrl()).isNull();
    }

    @Test
    void mediaView_recordSemantics_hashEqualsAndToString() {
        NeighborhoodFeedMediaView a = media(1);
        NeighborhoodFeedMediaView b = media(1);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("image/jpeg");
    }
}
