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

    /**
     * Every reaction the subject ever left, across all posts — live and
     * removed — in stable {@code (created_at, id)} order. The reaction is
     * the subject's stored personal data (which post they thanked, and
     * when), so it rides the b-2 export like every other first-party
     * community fact (b-5: a removed reaction is still stored data until
     * the retention window closes). The #484 review round added this leg
     * — the L47 layer had ridden V73 with no export coverage at all.
     */
    List<CommunityReactionExportEntry> exportReactionsForOwner(UUID userId);

    /**
     * L49 (the events layer): every event the subject ever organized —
     * upcoming, past and organizer-deleted — in stable
     * {@code (created_at, id)} order. The event is authored personal
     * data (title, description, the display labels), which is why it
     * rides the b-2 export at all (the L47 reactions' deliberate
     * exclusion reasoned from "no authored text" — the event carries
     * four authored columns, so it rides).
     */
    List<CommunityEventExportEntry> exportEventsForOwner(UUID userId);

    /**
     * L49: every seat the subject ever took — held and freed — in
     * stable {@code (created_at, id)} order. The seat is
     * identifiers-and-timestamps personal data (the attendance
     * declaration itself), the reaction row's own class of member data.
     */
    List<CommunityEventSeatExportEntry> exportEventSeatsForOwner(UUID userId);
}
