package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): THE single
 * routing contract — the dimensions §8.1 lists, each its own field:
 * <ul>
 *   <li>{@code eventType} — نوع حدث قانوني: a {@link NotificationType}
 *       value, the legal delivery vocabulary (never a free string);</li>
 *   <li>{@code sourceType}/{@code sourceId} — مصدر/معرف: which domain
 *       object the event speaks about;</li>
 *   <li>{@code recipientId} — مستلم: the ONE user this policy routes
 *       for (a fan-out is one policy per recipient, never a shared
 *       broadcast policy);</li>
 *   <li>{@code section}/{@code topic} — قسم/مجال، موضوع/فئة: the
 *       platform domain and the user-facing subject family, SEPARATE
 *       dimensions each with its own small vocabulary ({@link
 *       RoutingSection}, {@link NotificationTopic}) — §8.1's "لا enum
 *       عملاق لكل تركيبة قسم×موضوع×جغرافيا×قناة" is this record's own
 *       design rule: dimensions compose at read time, never explode as
 *       per-combination types;</li>
 *   <li>{@code geoScope} — نطاق جغرافي: the level-3 location the event
 *       is scoped to, or {@code null} when the event is not geo-scoped
 *       (a null scope skips the geo gate — the geo dimension applies
 *       only to scoped events);</li>
 *   <li>{@code priority}/{@code validUntil} — أولوية/صلاحية: the
 *       delivery rank and the validity instant (the official-alert
 *       family routes on validity; {@code null} = unbounded);</li>
 *   <li>{@code actorId} — الفاعل: the user whose act produced the event,
 *       for the self-mute rule (no notification to yourself about your
 *       own act); {@code null} when the actor is not a user (an
 *       institution, a job, the platform);</li>
 *   <li>{@code sourceEventId} — هوية idempotency: the deterministic
 *       event identity the delivery ledgers key on (the V180
 *       {@code notifications.source_event_id} pattern for the inbox, the
 *       V199 {@code notification_deliveries} ledger for the outbound
 *       channels) — a re-delivered publication derives the SAME key, so
 *       a retry can never duplicate a delivery.</li>
 * </ul>
 *
 * <p><b>Carried facts are not re-derived (§8.1):</b> this contract is
 * filled from the EVENT's own payload by the listener — a recipient the
 * event carries stays the recipient; the geo scope the event carries
 * stays the scope. The engine consults per-user STATE (membership,
 * subscriptions, preferences) only where the contract's own gates
 * demand it, never to guess a party the payload should have carried.
 *
 * @param eventType     the legal notification vocabulary the event maps to
 * @param sourceType    the domain family of the source object
 * @param sourceId      the source object's id
 * @param section       the platform section (قسم/مجال)
 * @param topic         the subject family (موضوع/فئة)
 * @param recipientId   the one recipient (مستلم)
 * @param geoScope      the level-3 geo scope (نطاق جغرافي), nullable
 * @param priority      the delivery rank (أولوية)
 * @param validUntil    the validity instant (صلاحية), nullable = unbounded
 * @param actorId       the acting user (self-mute dimension), nullable
 * @param sourceEventId the idempotency identity seed
 */
public record NotificationRoutingPolicy(
        NotificationType eventType,
        String sourceType,
        UUID sourceId,
        RoutingSection section,
        NotificationTopic topic,
        UUID recipientId,
        UUID geoScope,
        NotificationPriority priority,
        Instant validUntil,
        UUID actorId,
        UUID sourceEventId) {

    public NotificationRoutingPolicy {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        if (sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
    }

    /**
     * The event-shaped factory: the topic derives from the event type via
     * {@link NotificationTopic#topicFor(NotificationType)} — the single
     * mapping source — so a policy can never carry a topic its event type
     * does not belong to. Every dimension still arrives explicitly from
     * the event payload; nothing is inferred behind the caller's back.
     */
    public static NotificationRoutingPolicy forEvent(NotificationType eventType,
                                                     String sourceType, UUID sourceId,
                                                     RoutingSection section,
                                                     UUID recipientId, UUID geoScope,
                                                     NotificationPriority priority,
                                                     Instant validUntil, UUID actorId,
                                                     UUID sourceEventId) {
        return new NotificationRoutingPolicy(eventType, sourceType, sourceId, section,
                NotificationTopic.topicFor(eventType), recipientId, geoScope, priority,
                validUntil, actorId, sourceEventId);
    }
}
