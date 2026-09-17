package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L42 (neighborhood community plan §5): one comment the subject authored
 * in the account's Art. 20 export (the b-2 self-service read the
 * identity module aggregates through {@code CommunityExportPort}).
 *
 * <p>The comment travels with its post reference as the stored
 * {@code neighborhood_posts} id — the counterparty's post is an opaque
 * UUID from this record's seat, the same boundary rule every export
 * entry applies. Soft-deleted comments are included (b-5); a purged
 * account's comments carry the {@code [purged]} tombstone body the b-3
 * adapter left.
 */
public record CommunityCommentExportEntry(
        UUID id,
        UUID postId,
        String body,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted
) {
}
