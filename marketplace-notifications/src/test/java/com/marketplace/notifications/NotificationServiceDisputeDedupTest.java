package com.marketplace.notifications;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 5-f: the dispute deliveries' dedup contract — the
 * notifications.source_event_id ledger (V180) at the service level:
 * <ul>
 * <li>the first delivery of a source event creates ONE row keyed on the
 * source event id (the dispute's own id for the open acknowledgment, the
 * deterministic {@code nameUUIDFromBytes(disputeId + "-resolved")}
 * derivation for the resolve fact);</li>
 * <li>a RE-DELIVERED source event (the framework's resubmission of an
 * incomplete registry entry) is a silent early return — no second row,
 * no exception: re-delivery can never duplicate;</li>
 * <li>the concurrent-insert race (two listeners delivering the same event
 * past the pre-insert gate) loses quietly at the
 * {@code uq_notifications_source_event_once} partial unique index (V186):
 * the loser absorbs the {@code DataIntegrityViolationException} OUTSIDE
 * the delivery writer's own REQUIRES_NEW transaction (the
 * MessageSendWriter pattern — a caught violation inside the violated
 * transaction is rollback-only, no commit can follow it) because the
 * winner's row IS the delivery (the V150 replay discipline's twin);</li>
 * <li>every OTHER integrity violation propagates honestly — the absorb
 * is keyed on the ledger constraint's own name, never on the exception
 * type alone.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceDisputeDedupTest {

    private static final UUID DISPUTE_ID = UUID.randomUUID();
    private static final UUID OPENER_ID = UUID.randomUUID();

    @Mock
    private NotificationRepository repository;

    @Mock
    private BookingParticipantProvider bookingParticipantProvider;

    @Mock
    private PaymentIntentLookupPort paymentIntentLookupPort;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private EmailNotificationService emailNotificationService;

    @Mock
    private NotificationPreferenceService preferences;
    @Mock
    private com.marketplace.notifications.routing.NotificationDeliveryLedger deliveryLedger;

    private NotificationService service() {
        return new NotificationService(repository, bookingParticipantProvider,
                paymentIntentLookupPort, currentUserProvider, emailNotificationService,
                Optional.<SimpMessagingTemplate>empty(), preferences,
                new NotificationTextSource(), new DisputeDeliveryWriter(repository),
                new DisputeChannelDelivery(preferences, emailNotificationService,
                        Optional.<SimpMessagingTemplate>empty(), deliveryLedger,
                        new NotificationTextSource()));
    }

    @Test
    void onDisputeOpened_createsTheLedgeredRow() {

        service().onDisputeOpened(DISPUTE_ID, OPENER_ID);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).saveAndFlush(saved.capture());
        Notification row = saved.getValue();
        assertThat(row.getRecipientId()).isEqualTo(OPENER_ID);
        assertThat(row.getType()).isEqualTo(NotificationType.DISPUTE_OPENED.name());
        assertThat(row.getSourceEventId()).isEqualTo(DISPUTE_ID);
        assertThat(row.getMessage()).isNotBlank();
    }

    @Test
    void onDisputeOpened_reDeliveryIsAbsorbedAndTheChannelsStaySilent() {
        // CodeRabbit's final round on the #544 slice (fixed where the V199
        // per-channel ledger lives): the pre-fix exists() early return
        // stranded the channel legs — a send that failed once was never
        // retried because the inbox row existed. The re-delivery now
        // re-persists (the ledger row absorbs the duplicate), and the
        // channel legs consult their OWN per-channel ledger: what was
        // already delivered stays silent.
        when(repository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "\"uq_notifications_source_event_once\""));
        // The L22 preference gates must stand OPEN for this scenario — the
        // silence under test is the LEDGER's verdict, not the gate's. With
        // the gates shut (the unstubbed boolean default) the && would
        // short-circuit and the ledger stubs would never be consumed
        // (Mockito STRICT_STUBS: UnnecessaryStubbingException).
        when(preferences.isChannelEnabled(OPENER_ID, NotificationType.DISPUTE_OPENED,
                NotificationChannel.EMAIL)).thenReturn(true);
        when(preferences.isChannelEnabled(OPENER_ID, NotificationType.DISPUTE_OPENED,
                NotificationChannel.WS)).thenReturn(true);
        when(deliveryLedger.recordDelivery(eq(DISPUTE_ID), eq(OPENER_ID),
                eq(com.marketplace.notifications.routing.NotificationDeliveryChannel.EMAIL)))
                .thenReturn(false);
        when(deliveryLedger.recordDelivery(eq(DISPUTE_ID), eq(OPENER_ID),
                eq(com.marketplace.notifications.routing.NotificationDeliveryChannel.WS)))
                .thenReturn(false);

        assertThatCode(() -> service().onDisputeOpened(DISPUTE_ID, OPENER_ID))
                .doesNotThrowAnyException();
        // The proof of the silence: both channel legs DID consult the
        // per-channel ledger and it answered "already delivered".
        verify(deliveryLedger).recordDelivery(DISPUTE_ID, OPENER_ID,
                com.marketplace.notifications.routing.NotificationDeliveryChannel.EMAIL);
        verify(deliveryLedger).recordDelivery(DISPUTE_ID, OPENER_ID,
                com.marketplace.notifications.routing.NotificationDeliveryChannel.WS);
        verify(emailNotificationService, never()).sendEmail(any(), anyString(), anyString(), any());
    }

    @Test
    void onDisputeOpened_ledgerRaceIsAbsorbedAsTheWinnersProof() {
        when(repository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "\"uq_notifications_source_event_once\""));

        assertThatCode(() -> service().onDisputeOpened(DISPUTE_ID, OPENER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void onDisputeOpened_aForeignIntegrityViolationPropagates() {
        when(repository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_follows_one_live\""));

        assertThatThrownBy(() -> service().onDisputeOpened(DISPUTE_ID, OPENER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onDisputeResolved_usesTheDeterministicLedgerKey() {

        service().onDisputeResolved(DISPUTE_ID, OPENER_ID, "REFUND_CONSUMER", 5000L);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).saveAndFlush(saved.capture());
        Notification row = saved.getValue();
        assertThat(row.getRecipientId()).isEqualTo(OPENER_ID);
        assertThat(row.getType()).isEqualTo(NotificationType.DISPUTE_RESOLVED.name());
        assertThat(row.getSourceEventId()).isEqualTo(deterministicKey());
        // MessageFormat renders the minor-unit amount with its default
        // grouping ("5,000") — the assertion holds the dispute id (unformatted)
        // and the refund word, the ledger contract's real carriers.
        assertThat(row.getMessage()).contains(DISPUTE_ID.toString());
        assertThat(row.getMessage()).contains("استرداد");
    }

    @Test
    void onDisputeResolved_reDeliveryDerivesTheSameKeyAndTheLedgerAbsorbsIt() {
        when(repository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "\"uq_notifications_source_event_once\""));
        // The L22 preference gates stand OPEN — the silence under test is
        // the LEDGER's verdict on the deterministic key, not the gate's.
        when(preferences.isChannelEnabled(OPENER_ID, NotificationType.DISPUTE_RESOLVED,
                NotificationChannel.EMAIL)).thenReturn(true);
        when(preferences.isChannelEnabled(OPENER_ID, NotificationType.DISPUTE_RESOLVED,
                NotificationChannel.WS)).thenReturn(true);
        when(deliveryLedger.recordDelivery(eq(deterministicKey()), eq(OPENER_ID),
                eq(com.marketplace.notifications.routing.NotificationDeliveryChannel.EMAIL)))
                .thenReturn(false);
        when(deliveryLedger.recordDelivery(eq(deterministicKey()), eq(OPENER_ID),
                eq(com.marketplace.notifications.routing.NotificationDeliveryChannel.WS)))
                .thenReturn(false);

        assertThatCode(() ->
                service().onDisputeResolved(DISPUTE_ID, OPENER_ID, "NO_ACTION", null))
                .doesNotThrowAnyException();
        // The proof of the silence: both channel legs DID consult the
        // per-channel ledger with the SAME derived key and it answered
        // "already delivered".
        verify(deliveryLedger).recordDelivery(deterministicKey(), OPENER_ID,
                com.marketplace.notifications.routing.NotificationDeliveryChannel.EMAIL);
        verify(deliveryLedger).recordDelivery(deterministicKey(), OPENER_ID,
                com.marketplace.notifications.routing.NotificationDeliveryChannel.WS);
        verify(emailNotificationService, never()).sendEmail(any(), anyString(), anyString(), any());
    }

    private UUID deterministicKey() {
        return UUID.nameUUIDFromBytes(
                (DISPUTE_ID + "-resolved").getBytes(StandardCharsets.UTF_8));
    }
}
