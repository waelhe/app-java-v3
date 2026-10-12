package com.marketplace.disputes;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.DisputeOpenedEvent;
import com.marketplace.shared.api.DisputeResolvedEvent;
import com.marketplace.shared.api.PaymentRefundPort;
import com.marketplace.shared.api.RefundOutcome;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {

    @Mock
    private DisputeRepository repository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private BookingParticipantProvider bookingParticipantProvider;

    @Mock
    private PaymentRefundPort paymentRefundPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private DisputeService disputeService;

    private final Authentication authentication = mock(Authentication.class);

    @Test
    void openDisputeForParticipant() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        BookingInfo info = new BookingInfo(userId, UUID.randomUUID(), "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(info);
        when(repository.save(any(Dispute.class))).thenAnswer(i -> i.getArgument(0));

        Dispute dispute = disputeService.open(bookingId, "late arrival", authentication);

        assertThat(dispute.getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void listForBooking_asParticipant_returnsList() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        BookingInfo info = new BookingInfo(UUID.randomUUID(), userId, "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(info);
        Dispute dispute = Dispute.open(bookingId, userId, "noise");
        when(repository.findByBookingId(bookingId)).thenReturn(List.of(dispute));

        List<Dispute> result = disputeService.listForBooking(bookingId, authentication);

        assertThat(result).containsExactly(dispute);
    }

    @Test
    void listForBooking_asAdmin_returnsList() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(mock(BookingInfo.class));
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), "noise");
        when(repository.findByBookingId(bookingId)).thenReturn(List.of(dispute));

        List<Dispute> result = disputeService.listForBooking(bookingId, authentication);

        assertThat(result).containsExactly(dispute);
    }

    @Test
    void listForBooking_asNonParticipant_throws() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        BookingInfo info = new BookingInfo(UUID.randomUUID(), otherUserId, "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(info);

        assertThrows(AccessDeniedException.class,
                () -> disputeService.listForBooking(bookingId, authentication));
    }

    @Test
    void resolve_noAction_succeeds_withoutTouchingTheRefundPath() {
        UUID disputeId = UUID.randomUUID();
        Dispute dispute = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "damage");
        when(repository.findById(disputeId)).thenReturn(Optional.of(dispute));

        Dispute result = disputeService.resolve(disputeId, DisputeResolution.NO_ACTION, authentication);

        assertThat(result.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(result.getResolution()).isEqualTo(DisputeResolution.NO_ACTION);
        assertThat(result.getRefundPaymentId()).isNull();
        assertThat(result.getRefundedAmountCents()).isNull();
        verifyNoInteractions(paymentRefundPort);
    }

    @Test
    void resolve_releaseProvider_succeeds_withoutTouchingTheRefundPath() {
        UUID disputeId = UUID.randomUUID();
        Dispute dispute = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "damage");
        when(repository.findById(disputeId)).thenReturn(Optional.of(dispute));

        Dispute result = disputeService.resolve(disputeId, DisputeResolution.RELEASE_PROVIDER, authentication);

        assertThat(result.getResolution()).isEqualTo(DisputeResolution.RELEASE_PROVIDER);
        verifyNoInteractions(paymentRefundPort);
    }

    @Test
    void resolve_refundConsumer_executesTheRefundOnceAndRecordsTheMovement() {
        UUID disputeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), "damage");
        when(repository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(paymentRefundPort.refundForBooking(bookingId, null))
                .thenReturn(new RefundOutcome(paymentId, 5000L));

        Dispute result = disputeService.resolve(disputeId, DisputeResolution.REFUND_CONSUMER, authentication);

        assertThat(result.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(result.getResolution()).isEqualTo(DisputeResolution.REFUND_CONSUMER);
        assertThat(result.getRefundPaymentId()).isEqualTo(paymentId);
        assertThat(result.getRefundedAmountCents()).isEqualTo(5000L);
        verify(paymentRefundPort, times(1)).refundForBooking(bookingId, null);
    }

    @Test
    void resolve_refundConsumer_refundFailure_propagates() {
        UUID disputeId = UUID.randomUUID();
        Dispute dispute = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "damage");
        when(repository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(paymentRefundPort.refundForBooking(any(), any()))
                .thenThrow(new ResourceNotFoundException("No payment intent for booking"));

        assertThrows(ResourceNotFoundException.class,
                () -> disputeService.resolve(disputeId, DisputeResolution.REFUND_CONSUMER, authentication));
    }

    @Test
    void resolve_alreadyResolved_throws409_beforeAnyRefundAttempt() {
        // L24 acceptance 1: a repeated resolve request is a conflict BEFORE
        // the refund path is invoked — no double debit is even attempted.
        UUID disputeId = UUID.randomUUID();
        Dispute dispute = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "damage");
        dispute.resolve(DisputeResolution.REFUND_CONSUMER);
        dispute.recordRefund(UUID.randomUUID(), 5000L);
        when(repository.findById(disputeId)).thenReturn(Optional.of(dispute));

        assertThrows(ConflictException.class,
                () -> disputeService.resolve(disputeId, DisputeResolution.REFUND_CONSUMER, authentication));
        verifyNoInteractions(paymentRefundPort);
    }

    @Test
    void resolve_notFound_throws() {
        UUID disputeId = UUID.randomUUID();
        when(repository.findById(disputeId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> disputeService.resolve(disputeId, DisputeResolution.NO_ACTION, authentication));
        verifyNoInteractions(paymentRefundPort);
    }

    /**
     * B-06 (compliance plan 0.7 — Modulith events.html): opening a dispute
     * publishes DisputeOpenedEvent — the module's first application event
     * (the measured defect §3.4-5: zero events, nobody could subscribe to
     * the pipeline's entry).
     */
    @Test
    void open_publishesDisputeOpenedEvent() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        BookingInfo info = new BookingInfo(userId, UUID.randomUUID(), "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(info);
        when(repository.save(any(Dispute.class))).thenAnswer(i -> i.getArgument(0));

        disputeService.open(bookingId, "late arrival", authentication);

        ArgumentCaptor<DisputeOpenedEvent> captor = ArgumentCaptor.forClass(DisputeOpenedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().bookingId()).isEqualTo(bookingId);
        assertThat(captor.getValue().openedBy()).isEqualTo(userId);
        assertThat(captor.getValue().disputeId()).isNotNull();
    }

    /**
     * B-06 (0.7): the PARTIAL refund activation — the admin's amount flows
     * to the payments port (which has carried the capability since L24)
     * and the dispute records the EXECUTED cumulative outcome, exactly as
     * the full-refund path does.
     */
    @Test
    void resolve_refundConsumer_partialAmount_flowsToTheRefundPortAndRecordsTheOutcome() {
        UUID disputeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), "damage");
        when(repository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(paymentRefundPort.refundForBooking(bookingId, 2500L))
                .thenReturn(new RefundOutcome(paymentId, 2500L));

        Dispute result = disputeService.resolve(disputeId, DisputeResolution.REFUND_CONSUMER, 2500L, authentication);

        assertThat(result.getRefundPaymentId()).isEqualTo(paymentId);
        assertThat(result.getRefundedAmountCents()).isEqualTo(2500L);
        verify(paymentRefundPort, times(1)).refundForBooking(bookingId, 2500L);
    }

    /**
     * B-06 (0.7): money never moves implicitly — an amount on a
     * non-refund decision is a contract error (400) BEFORE any state
     * change or refund attempt.
     */
    @Test
    void resolve_amountOnNonRefundDecision_is400BeforeAnyMovement() {
        UUID disputeId = UUID.randomUUID();
        Dispute dispute = Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "damage");

        assertThrows(BadRequestException.class,
                () -> disputeService.resolve(disputeId, DisputeResolution.RELEASE_PROVIDER, 2500L, authentication));
        verifyNoInteractions(paymentRefundPort);
        // the guard is a request-shape contract check: it fires BEFORE the
        // repository is even consulted (no stub needed — Mockito's strict
        // discipline itself proves the ordering) and the dispute stays OPEN.
        assertThat(dispute.getStatus()).isEqualTo(DisputeStatus.OPEN);
        verifyNoInteractions(repository);
    }

    /**
     * B-06 (0.7 — the events half's gate): the resolve decision publishes
     * DisputeResolvedEvent carrying the EXECUTED financial outcome (the
     * cumulative refunded total on a refund decision; null on the
     * money-less decisions) — a consumer never re-derives money facts
     * from the enum.
     */
    @Test
    void resolve_publishesDisputeResolvedEventWithTheExecutedOutcome() {
        UUID disputeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), "damage");
        when(repository.findById(disputeId)).thenReturn(Optional.of(dispute));
        when(paymentRefundPort.refundForBooking(bookingId, null))
                .thenReturn(new RefundOutcome(paymentId, 5000L));

        disputeService.resolve(disputeId, DisputeResolution.REFUND_CONSUMER, authentication);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(DisputeResolvedEvent.class);
        DisputeResolvedEvent event = (DisputeResolvedEvent) captor.getValue();
        assertThat(event.disputeId()).isEqualTo(disputeId);
        assertThat(event.bookingId()).isEqualTo(bookingId);
        // The shared five-component contract (the complete-fact discipline):
        // the opener rides the payload (the notification's recipient) and
        // the resolution is the STORED name, never the disputes-domain enum.
        assertThat(event.openedBy()).isEqualTo(dispute.getOpenedBy());
        assertThat(event.resolution()).isEqualTo(DisputeResolution.REFUND_CONSUMER.name());
        assertThat(event.refundedAmountCents()).isEqualTo(5000L);
    }

    /**
     * B-06 (0.7): the money-less decision publishes the event too — with a
     * null outcome (RELEASE_PROVIDER: the money stays).
     */
    @Test
    void resolve_moneyLessDecision_publishesTheEventWithNullOutcome() {
        UUID disputeId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Dispute dispute = Dispute.open(bookingId, UUID.randomUUID(), "damage");
        when(repository.findById(disputeId)).thenReturn(Optional.of(dispute));

        disputeService.resolve(disputeId, DisputeResolution.RELEASE_PROVIDER, authentication);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        DisputeResolvedEvent event = (DisputeResolvedEvent) captor.getValue();
        assertThat(event.resolution()).isEqualTo(DisputeResolution.RELEASE_PROVIDER.name());
        assertThat(event.refundedAmountCents()).isNull();
    }
}
