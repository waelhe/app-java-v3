package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 7 (§8.1 — notification routing): the topic family mapping — the
 * hierarchical preference's second level — is TOTAL over the legal
 * NotificationType vocabulary (nineteen types, ten families, no gaps)
 * and loud on a gap (a new type without its mapping is a build-time
 * defect, never a silently unroutable event).
 */
class NotificationTopicTest {

    @Test
    void everyNotificationTypeMapsToExactlyOneTopic() {
        // 19 types / 10 families — the mapping is the single source of
        // truth the policy factory and the engine both read.
        for (NotificationType type : NotificationType.values()) {
            assertThat(NotificationTopic.topicFor(type))
                    .as("type %s must resolve to a topic family", type)
                    .isNotNull();
        }
    }

    @Test
    void theFamilyMembershipFollowsTheDocumentedGrouping() {
        assertThat(NotificationTopic.topicFor(NotificationType.BOOKING_CREATED))
                .isEqualTo(NotificationTopic.BOOKINGS);
        assertThat(NotificationTopic.topicFor(NotificationType.BOOKING_CONFIRMED))
                .isEqualTo(NotificationTopic.BOOKINGS);
        assertThat(NotificationTopic.topicFor(NotificationType.ORDER_CANCELLED))
                .isEqualTo(NotificationTopic.ORDERS);
        assertThat(NotificationTopic.topicFor(NotificationType.PAYMENT_STATE))
                .isEqualTo(NotificationTopic.PAYMENTS);
        assertThat(NotificationTopic.topicFor(NotificationType.MESSAGE_RECEIVED))
                .isEqualTo(NotificationTopic.MESSAGING);
        assertThat(NotificationTopic.topicFor(NotificationType.LEAD_RECEIVED))
                .isEqualTo(NotificationTopic.MESSAGING);
        assertThat(NotificationTopic.topicFor(NotificationType.POST_REACTED))
                .isEqualTo(NotificationTopic.COMMUNITY);
        assertThat(NotificationTopic.topicFor(NotificationType.NEW_LISTING_IN_NEIGHBORHOOD))
                .isEqualTo(NotificationTopic.NEIGHBORHOOD);
        assertThat(NotificationTopic.topicFor(NotificationType.FOLLOWED_PROVIDER_NEW_LISTING))
                .isEqualTo(NotificationTopic.NEIGHBORHOOD);
        assertThat(NotificationTopic.topicFor(NotificationType.SAVED_SEARCH_MATCH))
                .isEqualTo(NotificationTopic.SEARCH);
        assertThat(NotificationTopic.topicFor(NotificationType.MEMBERSHIP_VERIFIED))
                .isEqualTo(NotificationTopic.TRUST);
        assertThat(NotificationTopic.topicFor(NotificationType.CONTENT_MODERATED))
                .isEqualTo(NotificationTopic.TRUST);
        assertThat(NotificationTopic.topicFor(NotificationType.REPORT_RESOLVED))
                .isEqualTo(NotificationTopic.TRUST);
        assertThat(NotificationTopic.topicFor(NotificationType.DISPUTE_OPENED))
                .isEqualTo(NotificationTopic.DISPUTES);
        assertThat(NotificationTopic.topicFor(NotificationType.DISPUTE_RESOLVED))
                .isEqualTo(NotificationTopic.DISPUTES);
        // The official-alert family — the §8.1 validity/geo-routed one.
        assertThat(NotificationTopic.topicFor(NotificationType.URGENT_ALERT))
                .isEqualTo(NotificationTopic.OFFICIAL);
    }

    @Test
    void forEventDerivesTheTopicFromTheEventType() {
        NotificationRoutingPolicy policy = NotificationRoutingPolicy.forEvent(
                NotificationType.URGENT_ALERT, "URGENT_ALERT", java.util.UUID.randomUUID(),
                RoutingSection.OFFICIAL, java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), NotificationPriority.URGENT, null, null,
                java.util.UUID.randomUUID());

        assertThat(policy.topic()).isEqualTo(NotificationTopic.OFFICIAL);
    }
}
