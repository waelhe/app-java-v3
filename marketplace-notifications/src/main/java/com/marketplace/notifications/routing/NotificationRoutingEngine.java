package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationChannel;
import com.marketplace.notifications.NotificationPreferenceService;
import com.marketplace.shared.api.CommunityMembershipPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): THE single
 * routing engine — turns one {@link NotificationRoutingPolicy} into one
 * {@link RoutingDecision}, applying the gates in ONE documented order:
 *
 * <ol>
 *   <li><b>Self-mute (كتم الإشعارات الذاتية):</b> the actor IS the
 *       recipient — no notification about your own act, every channel
 *       suppressed with {@code SELF_ACTION}. The one rule the standing
 *       listeners carried per-listener (the PostCommentedEvent/PostReactedEvent
 *       criterion-4 skips) as a first-class contract dimension.</li>
 *   <li><b>Validity (الصلاحية):</b> a policy carrying {@code validUntil}
 *       that has passed routes to nobody — {@code EXPIRED}.</li>
 *   <li><b>Geography (النطاق الجغرافي):</b> a geo-scoped policy routes
 *       only when the recipient's EFFECTIVE SCOPE covers it. The
 *       effective scope is the union of (a) the recipient's ACTIVE
 *       community-membership neighborhood — THE DEFAULT, the documented
 *       assumption §8.1 requires ("الافتراضي نطاق العضوية الحالية") —
 *       and (b) the recipient's explicit opt-in subscriptions (V198).
 *       Outside the scope → {@code GEO_SCOPE}; note the gate consults
 *       the recipient's geographic STATE only — never an interaction
 *       preference.</li>
 *   <li><b>Channels — the §8.1 SEPARATE policies:</b>
 *       <ul>
 *         <li><b>inbox:</b> always on (the L22 standing rule: "inside
 *             the app always") — for EVERY priority including the
 *             official-urgent family. This is the official-alert
 *             policy's one documented override: the urgent alert's
 *             ROUTING is validity+geo (gates 1-3) and its inbox row
 *             never bows to an interaction preference; §6.2's "وتفضيلات
 *             المستخدم" governs the AMPLIFICATION channels below.</li>
 *         <li><b>email / WebSocket:</b> the hierarchical preference
 *             resolution — type-level row (V40) &gt; topic-level row
 *             (V198) &gt; the enabled default. The WebSocket is NOT the
 *             mobile push (§8.1 verbatim): it is the live broker topic.</li>
 *         <li><b>push:</b> the {@link PushNotificationChannel} seam —
 *             absent until the provider decision (D-10) lands; an absent
 *             provider suppresses with {@code CHANNEL_NOT_CONFIGURED}.
 *             The device tokens themselves are the D-10 decision's own
 *             schema, deliberately not created ahead of it.</li>
 *       </ul></li>
 * </ol>
 *
 * <p><b>What the engine never does:</b> it never guesses a recipient the
 * event should have carried (§8.1's "لا يشتق المستلمون" — the policy's
 * recipient arrives filled from the event payload), and it never
 * consults a preference to answer WHO is eligible — preferences shape
 * only the amplification channels for the recipient the geo+validity
 * gates already admitted.
 */
@Service
@Transactional(readOnly = true)
public class NotificationRoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(NotificationRoutingEngine.class);

    private final NotificationPreferenceService typePreferences;
    private final NotificationTopicPreferenceService topicPreferences;
    private final CommunityMembershipPort membershipPort;
    private final NotificationGeoSubscriptionRepository geoSubscriptions;
    private final Optional<PushNotificationChannel> pushChannel;
    private final Clock clock;

    public NotificationRoutingEngine(NotificationPreferenceService typePreferences,
                                     NotificationTopicPreferenceService topicPreferences,
                                     CommunityMembershipPort membershipPort,
                                     NotificationGeoSubscriptionRepository geoSubscriptions,
                                     Optional<PushNotificationChannel> pushChannel,
                                     Clock clock) {
        this.typePreferences = typePreferences;
        this.topicPreferences = topicPreferences;
        this.membershipPort = membershipPort;
        this.geoSubscriptions = geoSubscriptions;
        this.pushChannel = pushChannel;
        this.clock = clock;
    }

    /**
     * Routes one policy for its one recipient — the gates in the class
     * javadoc's documented order, first gate wins.
     *
     * @param policy the routing contract filled from the event payload
     * @return the per-recipient decision with its suppression reasons
     */
    public RoutingDecision route(NotificationRoutingPolicy policy) {
        // Gate 1 — the self-mute: the actor's own act never notifies the actor.
        if (policy.actorId() != null && policy.actorId().equals(policy.recipientId())) {
            log.debug("Routing suppressed (self-action): type={}, source={}, recipient={}",
                    policy.eventType(), policy.sourceId(), policy.recipientId());
            return RoutingDecision.suppressed(policy.recipientId(), RoutingSuppression.SELF_ACTION);
        }

        // Gate 2 — the validity instant: an expired policy routes to nobody.
        if (policy.validUntil() != null && !clock.instant().isBefore(policy.validUntil())) {
            log.debug("Routing suppressed (expired): type={}, source={}, validUntil={}",
                    policy.eventType(), policy.sourceId(), policy.validUntil());
            return RoutingDecision.suppressed(policy.recipientId(), RoutingSuppression.EXPIRED);
        }

        // Gate 3 — the geography: the effective scope (membership ∪ opt-in
        // subscriptions) must cover the event's own scope. A null scope is
        // an unscoped event — the gate does not apply.
        if (policy.geoScope() != null && !coversGeoScope(policy.recipientId(), policy.geoScope())) {
            log.debug("Routing suppressed (geo): type={}, source={}, scope={}, recipient={}",
                    policy.eventType(), policy.sourceId(), policy.geoScope(), policy.recipientId());
            return RoutingDecision.suppressed(policy.recipientId(), RoutingSuppression.GEO_SCOPE);
        }

        // Gate 4 — the channels: four separate policies (§8.1).
        boolean email = channelAllowed(policy, NotificationChannel.EMAIL);
        boolean webSocket = channelAllowed(policy, NotificationChannel.WS);
        Set<RoutingSuppression> suppressions = EnumSet.noneOf(RoutingSuppression.class);
        if (!email) {
            suppressions.add(channelSuppression(policy, NotificationChannel.EMAIL));
        }
        if (!webSocket) {
            suppressions.add(channelSuppression(policy, NotificationChannel.WS));
        }
        boolean push;
        if (pushChannel.isEmpty()) {
            push = false;
            suppressions.add(RoutingSuppression.CHANNEL_NOT_CONFIGURED);
        } else {
            // No stored push preference exists yet (the dimension lands with
            // the D-10 provider and its device tokens): a present provider
            // delivers by default — the WS default-on semantics until the
            // preference machinery arrives with the provider decision.
            push = true;
        }
        return new RoutingDecision(policy.recipientId(), true, email, webSocket, push,
                suppressions);
    }

    /**
     * The hierarchical resolution, one order (the class javadoc): the
     * type-level row (V40) answers first; an empty answer falls through
     * to the topic-level row (V198); an empty topic answer falls through
     * to the enabled default. An explicit {@code enabled = true} at any
     * level overrides a lower level's disable — the order is total.
     */
    private boolean channelAllowed(NotificationRoutingPolicy policy, NotificationChannel channel) {
        return typePreferences.findChannelOverride(policy.recipientId(), policy.eventType(),
                channel)
                .or(() -> topicPreferences.findChannelOverride(policy.recipientId(),
                        policy.topic(), channel))
                .orElse(true);
    }

    /** Names the gate that actually suppressed the channel (explainability). */
    private RoutingSuppression channelSuppression(NotificationRoutingPolicy policy,
                                                  NotificationChannel channel) {
        if (typePreferences.findChannelOverride(policy.recipientId(), policy.eventType(),
                channel).isPresent()) {
            return RoutingSuppression.TYPE_PREFERENCE;
        }
        return RoutingSuppression.TOPIC_PREFERENCE;
    }

    /**
     * The effective geo scope test: the opt-in subscription answers
     * first (an explicit index-backed read), then the membership — the
     * documented default. An honest empty membership answers false: no
     * widening, ever (the AC-02-02 discipline).
     */
    private boolean coversGeoScope(UUID recipientId, UUID geoScope) {
        if (geoSubscriptions.existsByUserIdAndLocationId(recipientId, geoScope)) {
            return true;
        }
        return membershipPort.getActiveNeighborhoodId(recipientId)
                .map(geoScope::equals)
                .orElse(false);
    }
}
