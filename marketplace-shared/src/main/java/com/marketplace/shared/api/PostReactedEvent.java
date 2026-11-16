package com.marketplace.shared.api;

import java.util.UUID;

/**
 * L47 (the Nextdoor-2026 completeness wave — gap #1, the reactions
 * layer): published by the community module when a member thanks a
 * VISIBLE post, consumed by the notifications module for the
 * {@code POST_REACTED} alert to the post's author.
 *
 * <p>The Modulith house pattern ({@code PostCommentedEvent} /
 * {@code BookingCreatedEvent} precedents): identifiers only, published
 * inside the reactor's own transaction so the publication registry entry
 * commits atomically with the reaction row — the
 * {@code @ApplicationModuleListener} consumer runs AFTER_COMMIT in its
 * own REQUIRES_NEW unit, and a failed delivery leaves the registry entry
 * incomplete for the framework's retry.
 *
 * <p><b>Self-thank is the listener's own policy</b> (the
 * {@code PostCommentedEvent} criterion-4 precedent verbatim): the event
 * carries BOTH ids and is published for every reaction — including the
 * author's own thanks on their own post — so the fact stays honest and
 * auditable in the registry; the notifications listener compares
 * {@code reactorId} against {@code postAuthorId} and skips the
 * notification when they match. A publisher-side skip would have made
 * the registry blind to self-thanks for no consumer's benefit.
 */
public record PostReactedEvent(
        UUID postId,
        UUID reactorId,
        UUID postAuthorId
) {
}
