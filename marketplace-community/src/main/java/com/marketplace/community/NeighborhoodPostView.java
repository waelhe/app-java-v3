package com.marketplace.community;

import com.marketplace.shared.api.MediaLookupPort;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The feed's read model (L42): the stored facts and nothing else — the
 * same projection discipline as the membership view. The author stays an
 * opaque UUID (the identity seams own any resolution the client does);
 * the geo tree's display names stay the public geo surface's concern.
 *
 * <p><b>L47 (the reactions layer) widens the projection with the two
 * reaction facts the feed read now carries:</b> {@code reactionsCount}
 * (the live thanks on the post — the grouped count over the page's ids)
 * and {@code reactedByMe} (the caller's own live voice, so the client
 * renders the filled heart honestly). Both are the caller-scoped read
 * model's own facts — the count is the same for every reader, the
 * caller's own flag is the projection's one per-reader field (the same
 * shape the membership view carries).
 *
 * <p><b>L48 (the media layer) widens the projection with the post's
 * photos:</b> {@code media} — the feed read's own one grouped port read
 * over the page's ids (the reactions pattern verbatim). The entries are
 * the community layer's own {@link PostMediaView} read model: presigned
 * GET URLs the media module computed, position order, nothing else — no
 * object key, no status, no storage fact crosses the module boundary.
 * A post with no photos carries the empty list, and a post that just
 * left the publish factory has none (the same zero-fresh stance the
 * reaction facts carry).
 *
 * <p><b>JT-20 (the lost-and-found lifecycle) widens the projection with
 * the report's own state:</b> {@code lostFoundState} — present ONLY when
 * the post's category is {@code LOST_FOUND} (the V171 column's own
 * documented shape: every other category carries null, so a GENERAL
 * post never renders a lost-and-found state it does not have). The
 * client renders the honest state: ACTIVE while the search is on,
 * RESOLVED / FOUND when the story ends.
 *
 * <p>The compatibility constructor (the nine-argument shape every
 * pre-L47 call site rode) keeps compiling: it delegates with the fresh
 * post's own zero-false facts — a post that just left the publish
 * factory has no reactions, no caller voice, and no photos yet (and a
 * non-LOST_FOUND post no lost-and-found state at all).
 */
public record NeighborhoodPostView(
        UUID id,
        UUID authorId,
        UUID locationId,
        String category,
        String title,
        String body,
        String status,
        String lostFoundState,
        long reactionsCount,
        boolean reactedByMe,
        List<PostMediaView> media,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * The state's one projection rule (JT-20): it travels ONLY on a
     * LOST_FOUND post — null for every other category, exactly as the
     * column is null for them.
     */
    private static String lostFoundStateOf(NeighborhoodPost post) {
        return post.getCategory() == PostCategory.LOST_FOUND
                && post.getLostFoundState() != null
                ? post.getLostFoundState().name()
                : null;
    }

    static NeighborhoodPostView of(NeighborhoodPost post) {
        return new NeighborhoodPostView(
                post.getId(),
                post.getAuthorId(),
                post.getLocationId(),
                post.getCategory().name(),
                post.getTitle(),
                post.getBody(),
                post.getStatus().name(),
                lostFoundStateOf(post),
                0L,
                false,
                List.of(),
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    /**
     * The feed read's factory: the stored facts plus the two
     * caller-scoped reaction facts the grouped count and the caller's
     * own live voice produced, plus the post's grouped media entries
     * (L48) mapped into the feed's own read model.
     */
    static NeighborhoodPostView of(NeighborhoodPost post, long reactionsCount, boolean reactedByMe,
                                   List<MediaLookupPort.PostMediaEntry> media) {
        return new NeighborhoodPostView(
                post.getId(),
                post.getAuthorId(),
                post.getLocationId(),
                post.getCategory().name(),
                post.getTitle(),
                post.getBody(),
                post.getStatus().name(),
                lostFoundStateOf(post),
                reactionsCount,
                reactedByMe,
                media.stream().map(PostMediaView::of).toList(),
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    /**
     * The pre-L47 shape (the write paths' echo: a created post has no
     * reactions; a deleted one no longer renders): the compatibility
     * constructor every existing call site rode, delegating with the
     * zero-fresh facts of a post no one has thanked yet — and, since
     * L48, no photos uploaded yet either.
     */
    public NeighborhoodPostView(UUID id, UUID authorId, UUID locationId,
                                String category, String title, String body, String status,
                                Instant createdAt, Instant updatedAt) {
        this(id, authorId, locationId, category, title, body, status,
                null, 0L, false, List.of(), createdAt, updatedAt);
    }
}
