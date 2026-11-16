package com.marketplace.shared.api;

import java.util.UUID;

/**
 * L42 (neighborhood community plan §5 — the posts/feed/comments layer):
 * published by the community module when a comment lands on a VISIBLE
 * post, consumed by the notifications module for the
 * {@code POST_COMMENTED} alert to the post's author.
 *
 * <p>The Modulith house pattern ({@code BookingCreatedEvent} /
 * {@code SavedSearchMatchedEvent} precedents): identifiers only,
 * published inside the commenter's own transaction so the publication
 * registry entry commits atomically with the comment row — the
 * {@code @ApplicationModuleListener} consumer runs AFTER_COMMIT in its
 * own REQUIRES_NEW unit, and a failed delivery leaves the registry entry
 * incomplete for the framework's retry (the plan's documented basis:
 * "the log entry stays untouched so that retry mechanisms can be
 * deployed").
 *
 * <p><b>Self-comment is the listener's own policy</b> (the plan's
 * criterion 4: "ما لم يكن المعلق هو المؤلف"): the event carries BOTH
 * ids and is published for every comment — including the author's own —
 * so the fact stays honest and auditable in the registry; the
 * notifications listener compares {@code commentAuthorId} against
 * {@code postAuthorId} and skips the notification when they match. A
 * publisher-side skip would have made the registry blind to self-comments
 * for no consumer's benefit.
 */
public record PostCommentedEvent(
        UUID postId,
        UUID commentAuthorId,
        UUID postAuthorId
) {
}
