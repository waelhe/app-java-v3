package com.marketplace.notifications.routing;

import com.marketplace.notifications.EmailNotificationService;
import com.marketplace.notifications.Notification;
import com.marketplace.notifications.NotificationRepository;
import com.marketplace.notifications.NotificationTextSource;
import com.marketplace.notifications.NotificationType;
import com.marketplace.shared.api.NeighborhoodMembersPort;
import com.marketplace.shared.api.UrgentAlertsPort;
import com.marketplace.shared.api.UrgentAlertPublishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * official-urgent-alert's notification leg — the
 * {@code UrgentAlertPublishedEvent} consumer that finally delivers the
 * {@code URGENT_ALERT} type the V180 registration reserved.
 *
 * <p><b>The routing contract (§8.1, the documented official-alert
 * policy):</b> the alert routes by VALIDITY and GEOGRAPHY, never by an
 * interaction preference —
 * <ul>
 *   <li><b>validity:</b> re-checked through the standing
 *       {@link UrgentAlertsPort} at routing time (the port answers ONLY
 *       alerts whose window covers {@code now} and that were not
 *       withdrawn) — a publication whose alert has since been withdrawn
 *       or has expired routes to NOBODY, and the re-check reads the
 *       source's CURRENT truth rather than a payload snapshot (a
 *       correction or withdrawal between publish and retry is honored);
 *       the port is also the eligibility engine the discovery row
 *       already rides, so the notification and every other surface read
 *       the SAME answer.</li>
 *   <li><b>geography:</b> the candidates are the alert's own level-3
 *       scope (the event's carried fact — never re-derived) resolved
 *       through its two data owners: the ACTIVE members
 *       ({@link NeighborhoodMembersPort}, the community module's
 *       enumeration) and the OPT-IN subscribers (V198, this module's own
 *       table) — then the engine's geo gate admits each recipient whose
 *       effective scope covers the alert's location. The send-volume
 *       question the URGENT_ALERT registration documented is answered by
 *       the shape: level-3-enforced scope at the source + per-channel
 *       gates + the inbox-only unconditional leg.</li>
 *   <li><b>channels:</b> the inbox row is the ONE unconditional leg (the
 *       official-alert policy's single override — a member who muted
 *       every channel still has the alert in their feed); EMAIL/WS ride
 *       the standing hierarchical preferences (§6.2: "وتفضيلات المستخدم
 *       والسياسات المعتمدة" — the alert forces no channel against an
 *       explicit opt-out); PUSH stays the D-10 seam. Every outbound leg
 *       is ledgered ({@link NotificationDeliveryLedger}) — a retried
 *       publication re-runs this method and lands on the skip branches:
 *       <b>a retry never duplicates a delivery</b> (the inbox row dedups
 *       on the V180 {@code source_event_id} ledger, the outbound legs on
 *       the V199 channel ledger).</li>
 * </ul>
 *
 * <p>The actor dimension is {@code null} by design: the alert's actor is
 * the DELEGATED INSTITUTION (the verified source), never a co-recipient
 * user — there is no self-mute to apply to an institutional alert.
 */
@Service
@Transactional
public class UrgentAlertNotificationRouter {

    private static final Logger log = LoggerFactory.getLogger(UrgentAlertNotificationRouter.class);

    private final NotificationRoutingEngine engine;
    private final NeighborhoodMembersPort membersPort;
    private final NotificationGeoSubscriptionRepository geoSubscriptions;
    private final UrgentAlertsPort urgentAlertsPort;
    private final NotificationRepository repository;
    private final EmailNotificationService emailNotificationService;
    private final UrgentAlertWebSocketPush webSocketPush;
    private final NotificationDeliveryLedger deliveryLedger;
    private final Optional<PushNotificationChannel> pushChannel;
    private final NotificationTextSource text;
    private final Clock clock;

    public UrgentAlertNotificationRouter(NotificationRoutingEngine engine,
                                         NeighborhoodMembersPort membersPort,
                                         NotificationGeoSubscriptionRepository geoSubscriptions,
                                         UrgentAlertsPort urgentAlertsPort,
                                         NotificationRepository repository,
                                         EmailNotificationService emailNotificationService,
                                         UrgentAlertWebSocketPush webSocketPush,
                                         NotificationDeliveryLedger deliveryLedger,
                                         Optional<PushNotificationChannel> pushChannel,
                                         NotificationTextSource text,
                                         Clock clock) {
        this.engine = engine;
        this.membersPort = membersPort;
        this.geoSubscriptions = geoSubscriptions;
        this.urgentAlertsPort = urgentAlertsPort;
        this.repository = repository;
        this.emailNotificationService = emailNotificationService;
        this.webSocketPush = webSocketPush;
        this.deliveryLedger = deliveryLedger;
        this.pushChannel = pushChannel;
        this.text = text;
        this.clock = clock;
    }

    /**
     * The {@code @ApplicationModuleListener} entry (the listener lives in
     * {@code NotificationEventListener}) — the fan-out for ONE published
     * alert, idempotent end to end.
     */
    public void onUrgentAlertPublished(UrgentAlertPublishedEvent event) {
        if (!stillActive(event)) {
            // Withdrawn or expired between the publication and this run —
            // the honest skip: the alert's own display semantics (404 to
            // the public) are the notification's too.
            log.info("Urgent alert {} no longer active at routing time — no fan-out",
                    event.alertId());
            return;
        }
        String message = text.compose("notification.URGENT_ALERT",
                NotificationTextSource.PLATFORM_LOCALE, event.sourceName(), event.title());
        Set<UUID> candidates = candidates(event);
        for (UUID recipient : candidates) {
            deliver(event, recipient, message);
        }
        log.info("Urgent alert fan-out completed: alertId={}, scope={}, candidates={}",
                event.alertId(), event.locationId(), candidates.size());
    }

    /**
     * The validity+withdrawal re-check — the {@link UrgentAlertsPort} is
     * the source's own live engine ("Only alerts whose validity window
     * covers now AND that were not withdrawn answer here"), shared with
     * the discovery row so every surface reads one truth.
     */
    private boolean stillActive(UrgentAlertPublishedEvent event) {
        return urgentAlertsPort.findActive(event.locationId(), clock.instant()).stream()
                .anyMatch(card -> card.alertId().equals(event.alertId()));
    }

    /**
     * The candidates: the scope's members (the community module's
     * enumeration port) UNION the scope's opt-in subscribers (this
     * module's own table) — the two data owners of the effective geo
     * scope. LinkedHashSet: one decision per candidate even when a user
     * is both member and subscriber.
     */
    private Set<UUID> candidates(UrgentAlertPublishedEvent event) {
        Set<UUID> candidates = new LinkedHashSet<>(membersPort.getActiveMemberIds(event.locationId()));
        geoSubscriptions.findByLocationId(event.locationId()).stream()
                .map(NotificationGeoSubscription::getUserId)
                .forEach(candidates::add);
        return candidates;
    }

    /**
     * One recipient's delivery — the engine decides, the ledgers guard,
     * the channels execute. The inbox row carries the V180 ledger key
     * (the alert's own id as the source event id); its partial unique
     * index is the concurrent backstop whose violation IS the winner's
     * proof (the dispute-delivery discipline verbatim).
     */
    private void deliver(UrgentAlertPublishedEvent event, UUID recipient, String message) {
        NotificationRoutingPolicy policy = NotificationRoutingPolicy.forEvent(
                NotificationType.URGENT_ALERT,
                "URGENT_ALERT",
                event.alertId(),
                RoutingSection.OFFICIAL,
                recipient,
                event.locationId(),
                NotificationPriority.URGENT,
                null, // validity is the PORT's live re-check — the source's own engine
                null, // the actor is the delegated institution, never a co-recipient
                event.alertId());
        RoutingDecision decision = engine.route(policy);
        if (!decision.inbox()) {
            return;
        }
        if (repository.existsByRecipientIdAndSourceEventId(recipient, event.alertId())) {
            log.debug("Urgent-alert inbox row already delivered: alertId={}, recipient={}",
                    event.alertId(), recipient);
        } else {
            try {
                repository.save(Notification.create(recipient,
                        NotificationType.URGENT_ALERT.name(), message, event.alertId()));
            } catch (DataIntegrityViolationException concurrentDuplicate) {
                log.info("Concurrent urgent-alert delivery lost the ledger race — the row "
                        + "exists: alertId={}, recipient={}", event.alertId(), recipient);
            }
        }
        if (decision.email()
                && deliveryLedger.recordDelivery(event.alertId(), recipient,
                        NotificationDeliveryChannel.EMAIL)) {
            emailNotificationService.sendEmail(recipient,
                    text.compose("email.URGENT_ALERT.subject",
                            NotificationTextSource.PLATFORM_LOCALE, event.sourceName()),
                    "email/notification",
                    java.util.Map.of("message", message));
        }
        if (decision.webSocket()
                && deliveryLedger.recordDelivery(event.alertId(), recipient,
                        NotificationDeliveryChannel.WS)) {
            webSocketPush.push(recipient, NotificationType.URGENT_ALERT, message);
        }
        if (decision.push()
                && deliveryLedger.recordDelivery(event.alertId(), recipient,
                        NotificationDeliveryChannel.PUSH)) {
            pushChannel.ifPresent(channel ->
                    channel.send(recipient, event.sourceName(), event.title()));
        }
    }
}
