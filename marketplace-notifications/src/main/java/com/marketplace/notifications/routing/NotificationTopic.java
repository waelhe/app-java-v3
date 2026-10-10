package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * موضوع/فئة dimension of the routing contract — the user-facing subject
 * family a notification belongs to, the layer the HIERARCHICAL topic
 * preference (V198 {@code notification_topic_preferences}) governs.
 *
 * <p><b>The hierarchy the engine resolves (one order, documented):</b>
 * the type-level override row (V40 {@code notification_preferences})
 * wins over the topic-level override row (V198) wins over the enabled
 * default — three levels, two override layers, resolved at read time.
 * The topic is a GROUPING of {@link NotificationType} values, never a
 * per-combination enum (the §8.1 rule): the vocabulary is ten families,
 * each mapping a fixed set of the nineteen legal types.
 *
 * <p>{@link #topicFor(NotificationType)} is the single mapping source —
 * the policy factory derives the topic from the event type, so a policy
 * can never carry a topic its event type does not belong to.
 */
public enum NotificationTopic {
    BOOKINGS,
    ORDERS,
    PAYMENTS,
    MESSAGING,
    COMMUNITY,
    NEIGHBORHOOD,
    SEARCH,
    TRUST,
    DISPUTES,
    OFFICIAL;

    /**
     * The type→topic family mapping — the single source of truth the
     * preference matrix and the routing engine both read. A new type
     * lands here exactly once (the L34 one-point-addition discipline).
     */
    private static final Map<NotificationType, NotificationTopic> BY_TYPE =
            buildTypeIndex();

    private static Map<NotificationType, NotificationTopic> buildTypeIndex() {
        Map<NotificationType, NotificationTopic> index = new EnumMap<>(NotificationType.class);
        for (NotificationTopic topic : values()) {
            for (NotificationType type : typesOf(topic)) {
                index.put(type, topic);
            }
        }
        return Map.copyOf(index);
    }

    private static NotificationType[] typesOf(NotificationTopic topic) {
        return switch (topic) {
            case BOOKINGS -> new NotificationType[] {
                    NotificationType.BOOKING_CREATED, NotificationType.BOOKING_CONFIRMED };
            case ORDERS -> new NotificationType[] {
                    NotificationType.ORDER_CONFIRMED, NotificationType.ORDER_FULFILLED,
                    NotificationType.ORDER_CANCELLED };
            case PAYMENTS -> new NotificationType[] { NotificationType.PAYMENT_STATE };
            case MESSAGING -> new NotificationType[] {
                    NotificationType.MESSAGE_RECEIVED, NotificationType.LEAD_RECEIVED };
            case COMMUNITY -> new NotificationType[] {
                    NotificationType.POST_COMMENTED, NotificationType.POST_REACTED };
            case NEIGHBORHOOD -> new NotificationType[] {
                    NotificationType.NEW_LISTING_IN_NEIGHBORHOOD,
                    NotificationType.FOLLOWED_PROVIDER_NEW_LISTING };
            case SEARCH -> new NotificationType[] { NotificationType.SAVED_SEARCH_MATCH };
            case TRUST -> new NotificationType[] {
                    NotificationType.MEMBERSHIP_VERIFIED, NotificationType.CONTENT_MODERATED,
                    NotificationType.REPORT_RESOLVED };
            case DISPUTES -> new NotificationType[] {
                    NotificationType.DISPUTE_OPENED, NotificationType.DISPUTE_RESOLVED };
            case OFFICIAL -> new NotificationType[] { NotificationType.URGENT_ALERT };
        };
    }

    /**
     * @param type the notification type being routed
     * @return the topic family the type belongs to — the hierarchical
     *         preference's second resolution level
     * @throws IllegalArgumentException for a type missing from the index
     *         (an enum addition without its mapping — a build-time-shaped
     *         defect surfaced loudly, never a silent unroutable type)
     */
    public static NotificationTopic topicFor(NotificationType type) {
        NotificationTopic topic = BY_TYPE.get(type);
        if (topic == null) {
            throw new IllegalArgumentException(
                    "NotificationType has no topic mapping: " + type);
        }
        return topic;
    }
}
