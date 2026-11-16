package com.marketplace.shared.api;

import java.util.UUID;

/**
 * L45 (neighborhood community plan §5 — the moderation &amp; reports layer):
 * published by the community module's resolve command when
 * {@code HIDE_CONTENT} hides a reported piece of content, consumed by the
 * notifications module for the {@code CONTENT_MODERATED} alert to the
 * content's author.
 *
 * <p>The Modulith house pattern ({@code PostCommentedEvent} /
 * {@code NewListingInNeighborhoodEvent} precedents): identifiers only,
 * published inside the resolver's own transaction so the publication
 * registry entry commits atomically with the hide and the report's
 * {@code RESOLVED} close — the {@code @ApplicationModuleListener}
 * consumer runs AFTER_COMMIT in its own REQUIRES_NEW unit, and a failed
 * delivery leaves the registry entry incomplete for the framework's
 * retry (the documented basis: "the log entry stays untouched so that
 * retry mechanisms can be deployed").
 *
 * <p>{@code targetType} is the community domain's stored name
 * ({@code "POST"}/{@code "COMMENT"} — the
 * {@code PaymentStateChangedEvent} String-vocabulary precedent: the event
 * carries the stored name, not the owning module's enum, so shared-api
 * stays free of community-domain types). The event fires only on the
 * real VISIBLE→HIDDEN transition (an already-hidden or author-deleted
 * target carries no new fact for the author — the resolver's documented
 * skip).
 */
public record ContentModeratedEvent(
        UUID recipientId,
        String targetType,
        UUID targetId
) {
}
