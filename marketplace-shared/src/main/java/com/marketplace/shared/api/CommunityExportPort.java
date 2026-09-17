package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * L41 (neighborhood community plan §5): the community module's
 * contribution to the account export (the standing {@code *ExportPort}
 * house pattern, R3 — the identity module's aggregation sees only this
 * shared-api type, exactly like {@code SavedSearchExportPort} before
 * it). The export includes the subject's soft-deleted memberships too:
 * a left neighborhood is still the subject's stored personal data (their
 * place of residence declaration) until the retention window closes
 * (b-5's discrimination — deletion at the surface is a visibility flag,
 * not an erasure).
 *
 * <p>L42 (the posts/feed/comments layer): the same port widens to the
 * subject's own posts and comments — the authored-text share of the
 * export (the plan's own scope: "توسيع CommunityExportPort (منشورات/
 * تعليقات المستخدم)"). The identity aggregation sees only these three
 * shared-api shapes; the module boundary does not move.
 */
public interface CommunityExportPort {

    /**
     * Every membership the subject ever declared — active and left — in
     * stable {@code (created_at, id)} order. The membership is personal
     * data (the user's self-declared location), which is why it rides
     * the b-2 export at all.
     */
    List<CommunityMembershipExportEntry> exportForOwner(UUID userId);

    /**
     * Every post the subject ever published — visible, hidden by
     * moderation and author-deleted — in stable {@code (created_at, id)}
     * order. The post is authored personal data, which is why it rides
     * the b-2 export (b-5: a deleted post is still the subject's stored
     * text until the retention window closes).
     */
    List<CommunityPostExportEntry> exportPostsForOwner(UUID userId);

    /**
     * Every comment the subject ever wrote, across all posts, in stable
     * {@code (created_at, id)} order — the same b-2/b-5 reasoning as the
     * posts.
     */
    List<CommunityCommentExportEntry> exportCommentsForOwner(UUID userId);
}
