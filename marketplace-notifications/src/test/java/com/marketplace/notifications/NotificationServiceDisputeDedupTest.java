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
import static org.mockito.ArgumentMatchers.any;
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
 * {@code uq_notifications_source_event_once} partial unique index — the
 * loser absorbs the {@code DataIntegrityViolationException} because the
 * winner's row IS the delivery (the V150 replay discipline's twin).</li>
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

    private NotificationService service() {
        return new NotificationService(repository, bookingParticipantProvider,
                paymentIntentLookupPort, currentUserProvider, emailNotificationService,
                Optional.<SimpMessagingTemplate>empty(), preferences,
                new NotificationTextSource());
    }

    @Test
    void onDisputeOpened_createsTheLedgeredRow() {
        when(repository.existsByRecipientIdAndSourceEventId(OPENER_ID, DISPUTE_ID)).thenReturn(false);

        service().onDisputeOpened(DISPUTE_ID, OPENER_ID);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        Notification row = saved.getValue();
        assertThat(row.getRecipientId()).isEqualTo(OPENER_ID);
        assertThat(row.getType()).isEqualTo(NotificationType.DISPUTE_OPENED.name());
        assertThat(row.getSourceEventId()).isEqualTo(DISPUTE_ID);
        assertThat(row.getMessage()).isNotBlank();
    }

    @Test
    void onDisputeOpened_reDeliveryIsASilentEarlyReturn() {
        when(repository.existsByRecipientIdAndSourceEventId(OPENER_ID, DISPUTE_ID)).thenReturn(true);

        service().onDisputeOpened(DISPUTE_ID, OPENER_ID);

        verify(repository, never()).save(any(Notification.class));
    }

    @Test
    void onDisputeOpened_ledgerRaceIsAbsorbedAsTheWinnersProof() {
        when(repository.existsByRecipientIdAndSourceEventId(OPENER_ID, DISPUTE_ID)).thenReturn(false);
        when(repository.save(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("uq_notifications_source_event_once"));

        assertThatCode(() -> service().onDisputeOpened(DISPUTE_ID, OPENER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void onDisputeResolved_usesTheDeterministicLedgerKey() {
        when(repository.existsByRecipientIdAndSourceEventId(OPENER_ID, deterministicKey()))
                .thenReturn(false);

        service().onDisputeResolved(DISPUTE_ID, OPENER_ID, "REFUND_CONSUMER", 5000L);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
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
    void onDisputeResolved_reDeliveryDerivesTheSameKeyAndIsASilentEarlyReturn() {
        when(repository.existsByRecipientIdAndSourceEventId(OPENER_ID, deterministicKey()))
                .thenReturn(true);

        service().onDisputeResolved(DISPUTE_ID, OPENER_ID, "NO_ACTION", null);

        verify(repository, never()).save(any(Notification.class));
    }

    private UUID deterministicKey() {
        return UUID.nameUUIDFromBytes(
                (DISPUTE_ID + "-resolved").getBytes(StandardCharsets.UTF_8));
    }
}
