package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationChannel;
import com.marketplace.notifications.NotificationPreferenceService;
import com.marketplace.notifications.NotificationType;
import com.marketplace.shared.api.CommunityMembershipPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 7 (§8.1 — notification routing): the single routing engine's
 * gate order and the four SEPARATE channel policies. The tests assert
 * the ACTUAL answer against the effective state (§11.2: the actual
 * response matches the effective preference) and carry the documented
 * official-alert policy:
 * <ul>
 *   <li>the gates in order: self-mute → validity → geo → channels;</li>
 *   <li>the hierarchical preference resolution (type &gt; topic &gt;
 *       default) governs EMAIL/WS;</li>
 *   <li>the inbox is always on — the official-alert policy's ONE
 *       documented override: an URGENT alert routes by validity+geo and
 *       its inbox row never bows to an interaction preference;</li>
 *   <li>disabling a channel stops THAT channel only;</li>
 *   <li>the WebSocket is not the push: the push leg answers on the D-10
 *       provider seam's presence alone.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NotificationRoutingEngineTest {

    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID NEIGHBORHOOD = UUID.randomUUID();
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"),
            ZoneOffset.UTC);

    @Mock
    private NotificationPreferenceService typePreferences;

    @Mock
    private NotificationTopicPreferenceService topicPreferences;

    @Mock
    private CommunityMembershipPort membershipPort;

    @Mock
    private NotificationGeoSubscriptionRepository geoSubscriptions;

    private NotificationRoutingEngine engine(Optional<PushNotificationChannel> pushChannel) {
        return new NotificationRoutingEngine(typePreferences, topicPreferences,
                membershipPort, geoSubscriptions, pushChannel, FIXED_CLOCK);
    }

    private static NotificationRoutingPolicy unscopedPolicy() {
        return NotificationRoutingPolicy.forEvent(NotificationType.POST_COMMENTED,
                "POST", SOURCE, RoutingSection.COMMUNITY, RECIPIENT, null,
                NotificationPriority.NORMAL, null, ACTOR, SOURCE);
    }

    private static NotificationRoutingPolicy scopedPolicy() {
        return NotificationRoutingPolicy.forEvent(NotificationType.URGENT_ALERT,
                "URGENT_ALERT", SOURCE, RoutingSection.OFFICIAL, RECIPIENT, NEIGHBORHOOD,
                NotificationPriority.URGENT, null, null, SOURCE);
    }

    @Test
    void aDefaultPolicyDeliversInboxEmailAndWebSocketAndHoldsThePush() {
        // No overrides stored anywhere, no membership gate (unscoped), no
        // push provider (D-10 pending): the decision names every channel
        // and explains the one held back.
        RoutingDecision decision = engine(Optional.empty()).route(unscopedPolicy());

        assertThat(decision.recipientId()).isEqualTo(RECIPIENT);
        assertThat(decision.inbox()).isTrue();
        assertThat(decision.email()).isTrue();
        assertThat(decision.webSocket()).isTrue();
        assertThat(decision.push()).isFalse();
        assertThat(decision.suppressions())
                .containsExactly(RoutingSuppression.CHANNEL_NOT_CONFIGURED);
    }

    @Test
    void theSelfActionMuteSuppressesEveryChannel() {
        // كتم الإشعارات الذاتية: the actor IS the recipient — no
        // notification about your own act, the one rule the standing
        // listeners carried per-listener as a contract dimension.
        NotificationRoutingPolicy selfAct = NotificationRoutingPolicy.forEvent(
                NotificationType.POST_COMMENTED, "POST", SOURCE, RoutingSection.COMMUNITY,
                RECIPIENT, null, NotificationPriority.NORMAL, null, RECIPIENT, SOURCE);

        RoutingDecision decision = engine(Optional.empty()).route(selfAct);

        assertThat(decision.inbox()).isFalse();
        assertThat(decision.email()).isFalse();
        assertThat(decision.webSocket()).isFalse();
        assertThat(decision.push()).isFalse();
        assertThat(decision.suppressions()).containsExactly(RoutingSuppression.SELF_ACTION);
        // The gates that answer first never touch the per-user state.
        verifyNoInteractions(typePreferences, topicPreferences, membershipPort,
                geoSubscriptions);
    }

    @Test
    void anExpiredPolicyRoutesToNobody() {
        NotificationRoutingPolicy expired = NotificationRoutingPolicy.forEvent(
                NotificationType.URGENT_ALERT, "URGENT_ALERT", SOURCE,
                RoutingSection.OFFICIAL, RECIPIENT, null, NotificationPriority.URGENT,
                FIXED_CLOCK.instant().minusSeconds(1), null, SOURCE);

        RoutingDecision decision = engine(Optional.empty()).route(expired);

        assertThat(decision.inbox()).isFalse();
        assertThat(decision.suppressions()).containsExactly(RoutingSuppression.EXPIRED);
    }

    @Test
    void aPolicyInsideItsValidityWindowRoutesNormally() {
        NotificationRoutingPolicy valid = NotificationRoutingPolicy.forEvent(
                NotificationType.URGENT_ALERT, "URGENT_ALERT", SOURCE,
                RoutingSection.OFFICIAL, RECIPIENT, null, NotificationPriority.URGENT,
                FIXED_CLOCK.instant().plusSeconds(3600), null, SOURCE);

        RoutingDecision decision = engine(Optional.empty()).route(valid);

        assertThat(decision.inbox()).isTrue();
        assertThat(decision.suppressions()).isEmpty();
    }

    @Test
    void theDefaultGeoScopeIsTheMembershipNeighborhood() {
        // The documented assumption: with no opt-in subscriptions, the
        // effective scope is the ACTIVE membership neighborhood — a
        // member of the alert's own scope passes; no membership never
        // widens (the honest empty → GEO_SCOPE).
        when(geoSubscriptions.existsByUserIdAndLocationId(RECIPIENT, NEIGHBORHOOD))
                .thenReturn(false);
        when(membershipPort.getActiveNeighborhoodId(RECIPIENT))
                .thenReturn(Optional.of(NEIGHBORHOOD));

        assertThat(engine(Optional.empty()).route(scopedPolicy()).inbox()).isTrue();

        when(membershipPort.getActiveNeighborhoodId(RECIPIENT)).thenReturn(Optional.empty());
        RoutingDecision decision = engine(Optional.empty()).route(scopedPolicy());
        assertThat(decision.inbox()).isFalse();
        assertThat(decision.suppressions()).containsExactly(RoutingSuppression.GEO_SCOPE);
    }

    @Test
    void anOptInGeoSubscriptionWidensTheEffectiveScope() {
        // The explicit geographic act: a live subscription to the alert's
        // scope admits a recipient the membership alone would not.
        when(geoSubscriptions.existsByUserIdAndLocationId(RECIPIENT, NEIGHBORHOOD))
                .thenReturn(true);

        RoutingDecision decision = engine(Optional.empty()).route(scopedPolicy());

        assertThat(decision.inbox()).isTrue();
        verify(membershipPort, never()).getActiveNeighborhoodId(any());
    }

    @Test
    void aMemberOfAnotherNeighborhoodNeverReceivesTheScopedEvent() {
        // The geo privacy gate: the membership covers a DIFFERENT
        // neighborhood — the scoped event routes to nobody for this
        // recipient, whatever their preferences say (routing is
        // validity+geo, not interaction preference).
        when(geoSubscriptions.existsByUserIdAndLocationId(RECIPIENT, NEIGHBORHOOD))
                .thenReturn(false);
        when(membershipPort.getActiveNeighborhoodId(RECIPIENT))
                .thenReturn(Optional.of(UUID.randomUUID()));

        RoutingDecision decision = engine(Optional.empty()).route(scopedPolicy());

        assertThat(decision.inbox()).isFalse();
        assertThat(decision.suppressions()).containsExactly(RoutingSuppression.GEO_SCOPE);
        verifyNoInteractions(typePreferences, topicPreferences);
    }

    @Test
    void aTopicLevelOptOutDisablesEmailAndWebSocketOnly() {
        // The hierarchy's second level: an OFFICIAL topic opt-out holds
        // EMAIL and WS — and ONLY those two channels (تعطيل قناة يوقف
        // توصيلها فقط). The inbox row and the push dimension answer
        // independently.
        when(topicPreferences.findChannelOverride(RECIPIENT, NotificationTopic.OFFICIAL,
                NotificationChannel.EMAIL)).thenReturn(Optional.of(false));
        when(topicPreferences.findChannelOverride(RECIPIENT, NotificationTopic.OFFICIAL,
                NotificationChannel.WS)).thenReturn(Optional.of(false));

        RoutingDecision decision = engine(Optional.empty()).route(scopedPolicy());

        assertThat(decision.inbox()).isTrue();
        assertThat(decision.email()).isFalse();
        assertThat(decision.webSocket()).isFalse();
        assertThat(decision.push()).isFalse();
        assertThat(decision.suppressions()).containsOnly(RoutingSuppression.TOPIC_PREFERENCE,
                RoutingSuppression.CHANNEL_NOT_CONFIGURED);
    }

    @Test
    void theTypeLevelRowOverridesTheDefaultBothWays() {
        // The documented total order: an explicit type-level row wins —
        // a type-level DISABLE holds the channel, a type-level ENABLE
        // delivers it (the override is a real state, not
        // last-disabled-wins); the topic level is consulted only when the
        // type level has no row (covered by the topic-opt-out test).
        when(typePreferences.findChannelOverride(RECIPIENT, NotificationType.URGENT_ALERT,
                NotificationChannel.EMAIL)).thenReturn(Optional.of(false));
        when(typePreferences.findChannelOverride(RECIPIENT, NotificationType.URGENT_ALERT,
                NotificationChannel.WS)).thenReturn(Optional.of(true));

        RoutingDecision decision = engine(Optional.empty()).route(scopedPolicy());

        assertThat(decision.email()).isFalse();
        assertThat(decision.webSocket()).isTrue();
        assertThat(decision.suppressions()).contains(RoutingSuppression.TYPE_PREFERENCE);
    }

    @Test
    void theUrgentAlertRoutesByValidityAndGeoNotByInteractionPreference() {
        // §8.1 verbatim: the official-urgent alert's ROUTING is the
        // validity-and-geo policy — a member of the scope with EVERY
        // interaction preference disabled still gets the INBOX row (the
        // official-alert policy's single documented override), while the
        // amplification channels honor their preferences (§6.2: "وفق
        // مستوى الخطورة وتفضيلات المستخدم والسياسات المعتمدة").
        when(geoSubscriptions.existsByUserIdAndLocationId(RECIPIENT, NEIGHBORHOOD))
                .thenReturn(false);
        when(membershipPort.getActiveNeighborhoodId(RECIPIENT))
                .thenReturn(Optional.of(NEIGHBORHOOD));
        when(typePreferences.findChannelOverride(RECIPIENT, NotificationType.URGENT_ALERT,
                NotificationChannel.EMAIL)).thenReturn(Optional.of(false));
        when(typePreferences.findChannelOverride(RECIPIENT, NotificationType.URGENT_ALERT,
                NotificationChannel.WS)).thenReturn(Optional.of(false));

        RoutingDecision decision = engine(Optional.empty()).route(scopedPolicy());

        assertThat(decision.inbox())
                .as("the official alert's inbox row survives every interaction mute")
                .isTrue();
        assertThat(decision.email()).isFalse();
        assertThat(decision.webSocket()).isFalse();
    }

    @Test
    void aConfiguredPushProviderActivatesThePushLeg() {
        // The D-10 seam: one implementation bean activates the channel —
        // zero engine changes (the preference machinery arrives WITH the
        // provider decision; until then a present provider delivers by
        // default, the WS default-on semantics).
        RoutingDecision decision = engine(Optional.of(new PushNotificationChannel() {
            @Override
            public void send(UUID recipientId, String title, String body) {
                // the D-10 provider's own delivery
            }
        })).route(unscopedPolicy());

        assertThat(decision.push()).isTrue();
        assertThat(decision.suppressions()).isEmpty();
    }

    @Test
    void theWebSocketDecisionIsIndependentOfTheEmailDecision() {
        // The channels are separate policies: an explicit type-level WS
        // opt-out holds ONLY the WS leg — the email leg keeps its own
        // answer (the standing L22 semantics, engine-encoded).
        when(typePreferences.findChannelOverride(RECIPIENT, NotificationType.POST_COMMENTED,
                NotificationChannel.WS)).thenReturn(Optional.of(false));
        when(typePreferences.findChannelOverride(RECIPIENT, NotificationType.POST_COMMENTED,
                NotificationChannel.EMAIL)).thenReturn(Optional.of(true));

        RoutingDecision decision = engine(Optional.empty()).route(unscopedPolicy());

        assertThat(decision.webSocket()).isFalse();
        assertThat(decision.email()).isTrue();
        assertThat(decision.inbox()).isTrue();
        assertThat(decision.suppressions()).contains(RoutingSuppression.TYPE_PREFERENCE);
    }
}
