package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L47 + the #484 review round: one reaction the subject left on a
 * neighborhood post, in the account's Art. 20 export (the b-2
 * self-service read the identity module aggregates through
 * {@code CommunityExportPort}).
 *
 * <p>The reaction is the subject's stored personal data — which post
 * they thanked, and when — exactly the class of first-party fact the
 * export contract collects. The post travels as its stored
 * {@code neighborhood_posts} id, the counterparty's post an opaque UUID
 * from this record's seat (the same boundary rule every export entry
 * applies). Soft-deleted reactions are included (b-5: a removed thank is
 * still the subject's stored fact until the retention window closes) —
 * the review round's measured gap had the reaction layer riding V73 with
 * no export leg at all, so a member requesting their data received no
 * record of their reactions.
 *
 * <p>No body, no type column: the L47 reaction is one shape ("the
 * reaction IS the fact" — V73's own vocabulary decision), so the entry
 * carries the identity, the target and the timestamps alone.
 */
public record CommunityReactionExportEntry(
        UUID id,
        UUID postId,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted
) {
}
