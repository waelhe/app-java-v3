package com.marketplace.notifications.routing;

import com.marketplace.notifications.EmailNotificationService;
import com.marketplace.notifications.Notification;
import com.marketplace.notifications.NotificationPreferenceService;
import com.marketplace.notifications.NotificationRepository;
import com.marketplace.notifications.NotificationTextSource;
import com.marketplace.notifications.NotificationType;
import com.marketplace.shared.api.CommunityMembershipPort;
import com.marketplace.shared.api.NeighborhoodMembersPort;
import com.marketplace.shared.api.UrgentAlertPublishedEvent;
import com.marketplace.shared.api.UrgentAlertsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 (§8.1 — notification routing): the official-urgent-alert's
 * notification leg — the fan-out end to end at the unit seam:
 * <ul>
 *   <li>the validity+withdrawal re-check through the standing
 *       {@code UrgentAlertsPort} — a withdrawn/expired alert routes to
 *       NOBODY (the alert's own display semantics are the
 *       notification's);</li>
 *   <li>the candidates are the scope's members UNION the scope's opt-in
 *       subscribers — one decision per candidate, a member-subscriber
 *       deduplicated by the set;</li>
 *   <li>the inbox row carries the V180 ledger key (the alert id as the
 *       source event id) and the concurrent-insert race is absorbed as
 *       the winner's proof;</li>
 *   <li><b>the retry contract: a re-delivered publication duplicates
 *       NOTHING</b> — the inbox pre-check answers, the channel ledger
 *       answers, no second row and no second send (the Phase 7 gate
 *       "إعادة المحاولة لا تضاعف الإشعارات أو التوزيع");</li>
 *   <li>the geo privacy: the port answers the alert's OWN scope only
 *       (verified by the exact argument) — a member of another
 *       neighborhood never enters the candidate set, and an engine GEO
 *       suppression routes the recipient to nobody.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UrgentAlertNotificationRouterTest {

    private static final UUID ALERT_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID SUBSCRIBER = UUID.randomUUID();
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

    @Mock
    private NeighborhoodMembersPort membersPort;

    @Mock
    private NotificationGeoSubscriptionRepository scopedSubscriptions;

    @Mock
    private UrgentAlertsPort urgentAlertsPort;

    @Mock
    private NotificationRepository repository;

    @Mock
    private EmailNotificationService emailNotificationService;

    @Mock
    private UrgentAlertWebSocketPush webSocketPush;

    @Mock
    private NotificationDeliveryLedger deliveryLedger;

    private UrgentAlertNotificationRouter router() {
        NotificationRoutingEngine engine = new NotificationRoutingEngine(typePreferences,
                topicPreferences, membershipPort, geoSubscriptions, Optional.empty(),
                FIXED_CLOCK);
        return new UrgentAlertNotificationRouter(engine, membersPort, scopedSubscriptions,
                urgentAlertsPort, repository, emailNotificationService, webSocketPush,
                deliveryLedger, Optional.empty(), new NotificationTextSource(), FIXED_CLOCK);
    }

    private static UrgentAlertPublishedEvent event() {
        return new UrgentAlertPublishedEvent(ALERT_ID, SOURCE_ID, "بلدية القديسة", LOCATION,
                "CRITICAL", "إغلاق طريق رئيسي", Instant.parse("2026-10-10T11:59:00Z"));
    }

    private void alertIsActive() {
        lenient().when(urgentAlertsPort.findActive(eq(LOCATION), any())).thenReturn(List.of(
                new UrgentAlertsPort.UrgentAlertCard(ALERT_ID, SOURCE_ID, "بلدية القديسة",
                        "MUNICIPALITY", "CRITICAL", "إغلاق طريق رئيسي", "التفاصيل", LOCATION,
                        Instant.parse("2026-10-10T11:00:00Z"),
                        Instant.parse("2026-10-10T18:00:00Z"),
                        Instant.parse("2026-10-10T11:59:00Z"))));
    }

    private void membersAndSubscribers() {
        lenient().when(membersPort.getActiveMemberIds(LOCATION)).thenReturn(List.of(MEMBER));
        lenient().when(scopedSubscriptions.findByLocationId(LOCATION)).thenReturn(List.of());
    }

    /**
     * The engine's geo gate admitting the MEMBER through the documented
     * default (the active membership covers the alert's scope) — the
     * candidate's eligibility, separate from the candidate enumeration.
     */
    private void admitMemberThroughMembership() {
        lenient().when(geoSubscriptions.existsByUserIdAndLocationId(MEMBER, LOCATION))
                .thenReturn(false);
        lenient().when(membershipPort.getActiveNeighborhoodId(MEMBER))
                .thenReturn(Optional.of(LOCATION));
    }

    @Test
    void theFanOutDeliversTheInboxRowToTheScopeMemberWithTheLedgerKey() {
        alertIsActive();
        membersAndSubscribers();
        admitMemberThroughMembership();

        router().onUrgentAlertPublished(event());

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRecipientId()).isEqualTo(MEMBER);
        assertThat(saved.getValue().getType()).isEqualTo(NotificationType.URGENT_ALERT.name());
        assertThat(saved.getValue().getSourceEventId())
                .as("the V180 ledger key IS the alert's id — a re-delivered publication "
                        + "derives the same key")
                .isEqualTo(ALERT_ID);
        assertThat(saved.getValue().getMessage()).contains("بلدية القديسة");
        // The re-check read the alert's OWN scope — never another one.
        verify(urgentAlertsPort).findActive(eq(LOCATION), any());
        verify(membersPort).getActiveMemberIds(LOCATION);
    }

    @Test
    void aWithdrawnOrExpiredAlertRoutesToNobody() {
        when(urgentAlertsPort.findActive(eq(LOCATION), any())).thenReturn(List.of());

        router().onUrgentAlertPublished(event());

        verify(repository, never()).save(any(Notification.class));
        verify(membersPort, never()).getActiveMemberIds(any());
        verify(emailNotificationService, never()).sendEmail(any(), anyString(), anyString(), any());
    }

    @Test
    void theOptInSubscriberOfTheScopeJoinsTheFanOut() {
        alertIsActive();
        when(membersPort.getActiveMemberIds(LOCATION)).thenReturn(List.of(MEMBER));
        NotificationGeoSubscription subscription =
                NotificationGeoSubscription.subscribe(SUBSCRIBER, LOCATION);
        when(scopedSubscriptions.findByLocationId(LOCATION)).thenReturn(List.of(subscription));
        admitMemberThroughMembership();
        lenient().when(geoSubscriptions.existsByUserIdAndLocationId(SUBSCRIBER, LOCATION))
                .thenReturn(true);

        router().onUrgentAlertPublished(event());

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Notification::getRecipientId)
                .containsExactlyInAnyOrder(MEMBER, SUBSCRIBER);
    }

    @Test
    void aMemberWhoIsAlsoASubscriberGetsExactlyOneInboxRow() {
        alertIsActive();
        when(membersPort.getActiveMemberIds(LOCATION)).thenReturn(List.of(MEMBER));
        when(scopedSubscriptions.findByLocationId(LOCATION)).thenReturn(List.of(
                NotificationGeoSubscription.subscribe(MEMBER, LOCATION)));
        admitMemberThroughMembership();

        router().onUrgentAlertPublished(event());

        verify(repository, times(1)).save(any(Notification.class));
    }

    @Test
    void aRetriedPublicationDuplicatesNothing() {
        // The framework's resubmission re-runs this listener with the same
        // event: the inbox pre-check answers (the row exists), the channel
        // ledger answers (the delivery exists) — no second row, no second
        // send, no exception (the registry entry completes cleanly).
        alertIsActive();
        membersAndSubscribers();
        admitMemberThroughMembership();
        when(repository.existsByRecipientIdAndSourceEventId(MEMBER, ALERT_ID)).thenReturn(true);
        when(deliveryLedger.recordDelivery(ALERT_ID, MEMBER, NotificationDeliveryChannel.EMAIL))
                .thenReturn(false);
        when(deliveryLedger.recordDelivery(ALERT_ID, MEMBER, NotificationDeliveryChannel.WS))
                .thenReturn(false);

        router().onUrgentAlertPublished(event());

        verify(repository, never()).save(any(Notification.class));
        verify(emailNotificationService, never()).sendEmail(any(), anyString(), anyString(), any());
        verify(webSocketPush, never()).push(any(), any(), anyString());
    }

    @Test
    void aChannelTheLedgerHasNotRecordedStillDeliversOnRetry() {
        // The partial-failure retry: the inbox row landed and the email
        // sent before the listener died — the WS leg (never recorded)
        // completes on the retry, the delivered legs stay silent.
        alertIsActive();
        membersAndSubscribers();
        admitMemberThroughMembership();
        when(repository.existsByRecipientIdAndSourceEventId(MEMBER, ALERT_ID)).thenReturn(true);
        when(deliveryLedger.recordDelivery(ALERT_ID, MEMBER, NotificationDeliveryChannel.EMAIL))
                .thenReturn(false);
        when(deliveryLedger.recordDelivery(ALERT_ID, MEMBER, NotificationDeliveryChannel.WS))
                .thenReturn(true);

        router().onUrgentAlertPublished(event());

        verify(repository, never()).save(any(Notification.class));
        verify(emailNotificationService, never()).sendEmail(any(), anyString(), anyString(), any());
        verify(webSocketPush).push(eq(MEMBER), eq(NotificationType.URGENT_ALERT), anyString());
    }

    @Test
    void theConcurrentInboxInsertRaceIsAbsorbedAsTheWinnersProof() {
        alertIsActive();
        membersAndSubscribers();
        admitMemberThroughMembership();
        when(repository.existsByRecipientIdAndSourceEventId(MEMBER, ALERT_ID)).thenReturn(false);
        when(repository.save(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "uq_notifications_source_event_once"));

        assertThatCode(() -> router().onUrgentAlertPublished(event()))
                .as("the loser of the ledger race absorbs the violation — the row exists, "
                        + "the delivery happened, the registry entry completes")
                .doesNotThrowAnyException();
    }

    @Test
    void aGeoSuppressedRecipientIsNeverSaved() {
        // The geo privacy leg through the real engine: the recipient is a
        // scope member (a candidate) whose effective geo scope does NOT
        // cover the alert's location (no membership elsewhere counted —
        // membership resolves empty) → the engine's GEO gate holds every
        // channel, nothing is saved.
        alertIsActive();
        when(membersPort.getActiveMemberIds(LOCATION)).thenReturn(List.of(MEMBER));
        when(scopedSubscriptions.findByLocationId(LOCATION)).thenReturn(List.of());
        when(geoSubscriptions.existsByUserIdAndLocationId(MEMBER, LOCATION)).thenReturn(false);
        when(membershipPort.getActiveNeighborhoodId(MEMBER)).thenReturn(Optional.empty());

        router().onUrgentAlertPublished(event());

        verify(repository, never()).save(any(Notification.class));
    }
}
