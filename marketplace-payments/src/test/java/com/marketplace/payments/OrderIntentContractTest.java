package com.marketplace.payments;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 6 (ADR-0002) — the order intent's money gate, unit-tested without
 * the PSP (the {@code PaymentsServiceTest} construction verbatim): the
 * deterministic idempotency key replays the SAME intent, a foreign payer
 * is refused, a zero amount is refused before any write, and the
 * cancellation's money half walks the intent's state machine exactly once
 * (the events prove it).
 */
class OrderIntentContractTest {

    private final PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentWebhookEventRepository webhookEventRepository = mock(PaymentWebhookEventRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final com.marketplace.shared.security.CurrentUserProvider currentUserProvider =
            mock(com.marketplace.shared.security.CurrentUserProvider.class);
    private final com.marketplace.shared.api.BookingParticipantProvider bookingParticipantProvider =
            mock(com.marketplace.shared.api.BookingParticipantProvider.class);
    private final PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PspChannel> pspChannel = mock(ObjectProvider.class);
    private final Authentication authentication = mock(Authentication.class);
    private final WebhookEventRecorder webhookEventRecorder = new WebhookEventRecorder(webhookEventRepository);
    private final PaymentIntentSettlementService settlementService = new PaymentIntentSettlementService(
            intentRepository, paymentRepository, eventPublisher, webhookEventRecorder);
    private final SettlementEligibilityService settlementEligibilityService = new SettlementEligibilityService(
            mock(com.marketplace.shared.api.LedgerReadPort.class), intentRepository, bookingParticipantProvider);
    private final PaymentRefundOperationJournal refundOperationJournal =
            mock(PaymentRefundOperationJournal.class);
    private final PaymentsService service = new PaymentsService(
            intentRepository, paymentRepository, webhookEventRepository, eventPublisher,
            currentUserProvider, bookingParticipantProvider, webhookSecurity,
            webhookEventRecorder, settlementService, settlementEligibilityService, pspChannel,
            new CommissionProperties(java.math.BigDecimal.valueOf(0.10)), refundOperationJournal);

    @Test
    void createOrderIntent_savesTheOrderOriginIntentWithTheDeterministicKey() {
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        when(intentRepository.findByIdempotencyKey("order-" + orderId)).thenReturn(Optional.empty());
        // The stage-8 service persists and returns the saved entity (the
        // event carries the persisted id — the save answers the same).
        when(intentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent intent = service.createOrderIntent(orderId, buyer, 17400L, "SAR");

        assertThat(intent.getOrderId()).isEqualTo(orderId);
        assertThat(intent.getOrigin()).isEqualTo("ORDER");
        assertThat(intent.getConsumerId()).isEqualTo(buyer);
        assertThat(intent.getIdempotencyKey()).isEqualTo("order-" + orderId);
        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.CREATED);
        verify(eventPublisher).publishEvent(new PaymentStateChangedEvent(intent.getId(), "INITIATED"));
    }

    @Test
    void createOrderIntent_replaysTheSameIntentForTheSameOrder() {
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        PaymentIntent existing = PaymentIntent.createForOrder(orderId, buyer, 17400L, "SAR", "order-" + orderId, null);
        when(intentRepository.findByIdempotencyKey("order-" + orderId)).thenReturn(Optional.of(existing));

        PaymentIntent replayed = service.createOrderIntent(orderId, buyer, 17400L, "SAR");

        assertThat(replayed).isSameAs(existing);
        verify(intentRepository, never()).save(any());
    }

    @Test
    void createOrderIntent_refusesAForeignPayerReplay() {
        UUID orderId = UUID.randomUUID();
        PaymentIntent existing = PaymentIntent.createForOrder(orderId, UUID.randomUUID(),
                17400L, "SAR", "order-" + orderId, null);
        when(intentRepository.findByIdempotencyKey("order-" + orderId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createOrderIntent(orderId, UUID.randomUUID(), 17400L, "SAR"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createOrderIntent_refusesAZeroAmountBeforeAnyWrite() {
        UUID orderId = UUID.randomUUID();
        when(intentRepository.findByIdempotencyKey("order-" + orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrderIntent(orderId, UUID.randomUUID(), 0L, "SAR"))
                .isInstanceOf(ConflictException.class);
        verify(intentRepository, never()).save(any());
    }

    @Test
    void createOrderIntent_normalizesTheCurrencyBeforeTheReplayComparison() {
        // The part-1 review note: the stored intent carries the factory's own
        // normalized "SAR" — a retry arriving as "sar" (or null, the house
        // default) must meet it as the SAME payable, never a 409.
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        PaymentIntent existing = PaymentIntent.createForOrder(orderId, buyer, 17400L, "SAR", "order-" + orderId, null);
        when(intentRepository.findByIdempotencyKey("order-" + orderId)).thenReturn(Optional.of(existing));

        PaymentIntent replayedLowercase = service.createOrderIntent(orderId, buyer, 17400L, "sar");
        PaymentIntent replayedNull = service.createOrderIntent(orderId, buyer, 17400L, null);

        assertThat(replayedLowercase).isSameAs(existing);
        assertThat(replayedNull).isSameAs(existing);
        verify(intentRepository, never()).save(any());
    }

    @Test
    void createLoanIntent_normalizesTheCurrencyBeforeTheReplayComparison() {
        // The order path's twin (the same review note): the deterministic
        // loan key replays the SAME intent for the normalized form of the
        // same currency — "sar" and null both meet the stored "SAR".
        UUID loanId = UUID.randomUUID();
        UUID borrower = UUID.randomUUID();
        PaymentIntent existing = PaymentIntent.createForLoan(loanId, borrower, 17400L, "SAR", "loan-" + loanId, null);
        when(intentRepository.findByIdempotencyKey("loan-" + loanId)).thenReturn(Optional.of(existing));

        PaymentIntent replayedLowercase = service.createLoanIntent(loanId, borrower, 17400L, "sar");
        PaymentIntent replayedNull = service.createLoanIntent(loanId, borrower, 17400L, null);

        assertThat(replayedLowercase).isSameAs(existing);
        assertThat(replayedNull).isSameAs(existing);
        verify(intentRepository, never()).save(any());
    }

    @Test
    void autoRefundByOrder_cancelsTheUnpaidIntent() {
        UUID orderId = UUID.randomUUID();
        PaymentIntent unpaid = PaymentIntent.createForOrder(orderId, UUID.randomUUID(),
                100L, "SAR", "order-" + orderId, null);
        when(intentRepository.findByOrderId(orderId)).thenReturn(Optional.of(unpaid));

        service.autoRefundByOrder(orderId);

        assertThat(unpaid.getStatus()).isEqualTo(PaymentIntentStatus.CANCELLED);
        verify(intentRepository).save(unpaid);
        verify(eventPublisher).publishEvent(new PaymentStateChangedEvent(unpaid.getId(), "CANCELLED"));
    }

    @Test
    void autoRefundByOrder_refundsTheCollectedIntentInFull() {
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        PaymentIntent paid = PaymentIntent.createForOrder(orderId, buyer, 100L, "SAR", "order-" + orderId, null);
        // The stage-8 machine's own edges: CREATED -> PROCESSING -> SUCCEEDED
        // (a direct CREATED -> SUCCEEDED jump is the ConflictException).
        paid.markProcessing();
        paid.markSucceeded();
        // No local payment row (never charged through settlement) — the
        // intent-level terminal mark is the honest state, and the REFUNDED
        // event still fires for the ledger's mirror.
        when(intentRepository.findByOrderId(orderId)).thenReturn(Optional.of(paid));

        service.autoRefundByOrder(orderId);

        assertThat(paid.getStatus()).isEqualTo(PaymentIntentStatus.REFUNDED);
        assertThat(paid.getRefundedAmountCents()).isEqualTo(100L);
        verify(intentRepository).save(paid);
        verify(eventPublisher).publishEvent(new PaymentStateChangedEvent(paid.getId(), "REFUNDED"));
    }

    // ------------------------------------------------------------------
    // CodeRabbit agent findings, rows 17/18 — the derived-key namespace
    // must answer ONLY the payable it was derived from
    // ------------------------------------------------------------------

    @Test
    void createOrderIntent_refusesAKeyPreOccupiedByABookingIntent() {
        // The finding's exact attack: a CONSUMER books a cheap booking with
        // the caller-selected key "order-{orderId}" (the expensive order's
        // own derived key), then opens the order checkout — the replay must
        // NOT hand the booking intent to the order's payment gate.
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        PaymentIntent preOccupied = PaymentIntent.create(
                UUID.randomUUID(), buyer, 700L, "SAR", "order-" + orderId, null);
        when(intentRepository.findByIdempotencyKey("order-" + orderId))
                .thenReturn(Optional.of(preOccupied));

        assertThatThrownBy(() -> service.createOrderIntent(orderId, buyer, 17400L, "SAR"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("non-matching payment intent");
        verify(intentRepository, never()).save(any());
    }

    @Test
    void createOrderIntent_refusesAKeyHeldByAnAmountMismatchedOrderIntent() {
        // The complete binding check includes the AUTHORITATIVE amount: a
        // same-origin, same-order intent created under a different total
        // (a stale price snapshot) must never satisfy the current payable.
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        PaymentIntent stale = PaymentIntent.createForOrder(
                orderId, buyer, 9900L, "SAR", "order-" + orderId, null);
        when(intentRepository.findByIdempotencyKey("order-" + orderId)).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.createOrderIntent(orderId, buyer, 17400L, "SAR"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("non-matching payment intent");
    }

    @Test
    void createLoanIntent_refusesAKeyPreOccupiedByABookingIntent() {
        // Row 17's exact attack: the borrower pre-occupies "loan-{loanId}"
        // with a cheap booking intent, then the loan approval asks for its
        // payable — the replay must refuse, never link unrelated money.
        UUID loanId = UUID.randomUUID();
        UUID borrower = UUID.randomUUID();
        PaymentIntent preOccupied = PaymentIntent.create(
                UUID.randomUUID(), borrower, 700L, "SAR", "loan-" + loanId, null);
        when(intentRepository.findByIdempotencyKey("loan-" + loanId))
                .thenReturn(Optional.of(preOccupied));

        assertThatThrownBy(() -> service.createLoanIntent(loanId, borrower, 50000L, "SAR"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("non-matching payment intent");
        verify(intentRepository, never()).save(any());
    }

    @Test
    void createLoanIntent_replaysTheSameIntentForTheSameLoan() {
        UUID loanId = UUID.randomUUID();
        UUID borrower = UUID.randomUUID();
        PaymentIntent existing = PaymentIntent.createForLoan(
                loanId, borrower, 50000L, "SAR", "loan-" + loanId, null);
        when(intentRepository.findByIdempotencyKey("loan-" + loanId)).thenReturn(Optional.of(existing));

        PaymentIntent replayed = service.createLoanIntent(loanId, borrower, 50000L, "SAR");

        assertThat(replayed).isSameAs(existing);
        verify(intentRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Agent findings 2026-10-26T13:11 export, row 39 — the ad window's
    // derived key answers ONLY the window's own payable (the same
    // two-layer model: the booking surface's prefix refusal is the
    // structural isolation, this is the defense in depth on replay)
    // ------------------------------------------------------------------

    @Test
    void createAdIntent_refusesAKeyPreOccupiedByASamePayerBookingIntent() {
        // Row 39's exact attack: the provider (as a booking consumer)
        // pre-occupies "ad-debit-{campaignId}-{windowStart}" with a cheap
        // BOOKING intent through the caller-writable key surface, then the
        // billing event arrives — the replay must refuse, never let the
        // booking intent complete the billing line as if the AD payable
        // existed.
        UUID providerId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        String key = "ad-debit-" + campaignId + "-2026-10-01";
        PaymentIntent preOccupied = PaymentIntent.create(
                UUID.randomUUID(), providerId, 700L, "SAR", key, null);
        when(intentRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(preOccupied));

        assertThatThrownBy(() -> service.createAdIntent(providerId, campaignId,
                LocalDate.parse("2026-10-01"), 7500L, "SAR", key))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("non-matching payment intent");
        verify(intentRepository, never()).save(any());
    }

    @Test
    void createAdIntent_refusesAKeyHeldByAnAmountMismatchedAdIntent() {
        // The complete binding check includes the AUTHORITATIVE amount and
        // currency: a same-origin, same-campaign intent frozen under a
        // different window's total must never satisfy this window's line.
        UUID providerId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        String key = "ad-debit-" + campaignId + "-2026-10-01";
        PaymentIntent stale = PaymentIntent.createForAds(
                providerId, campaignId, 1200L, "SAR", key, null);
        when(intentRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.createAdIntent(providerId, campaignId,
                LocalDate.parse("2026-10-01"), 7500L, "SAR", key))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("non-matching payment intent");
        verify(intentRepository, never()).save(any());
    }

    @Test
    void createAdIntent_replaysTheSameIntentForTheSameWindow() {
        UUID providerId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        String key = "ad-debit-" + campaignId + "-2026-10-01";
        PaymentIntent existing = PaymentIntent.createForAds(
                providerId, campaignId, 7500L, "SAR", key, null);
        when(intentRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        PaymentIntent replayed = service.createAdIntent(providerId, campaignId,
                LocalDate.parse("2026-10-01"), 7500L, "SAR", key);

        assertThat(replayed).isSameAs(existing);
        verify(intentRepository, never()).save(any());
    }
}
