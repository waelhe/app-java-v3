package com.marketplace.payments;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R10 (Wave 2) — the recovery sweep's decision table, unit level: which rows
 * are re-delivered, and which failure family each re-delivery outcome lands
 * in. The end-to-end money path (a stranded row settling the payment on real
 * PostgreSQL) is {@code WebhookInboxRecoveryIntegrationTest}'s contract; this
 * test pins the CLASSIFICATION so it holds regardless of the database.
 */
class WebhookInboxRecoveryTest {

    private final PaymentWebhookEventRepository repository = mock(PaymentWebhookEventRepository.class);
    private final PaymentsService paymentsService = mock(PaymentsService.class);
    private final WebhookEventRecorder recorder = mock(WebhookEventRecorder.class);
    private final WebhookInboxRecovery recovery =
            new WebhookInboxRecovery(repository, paymentsService, recorder, mock(DataSource.class));

    private static PaymentWebhookEvent row(String eventId, String eventType) {
        // The dispatched contract the row carries (payload omitted — the unit
        // decision table reads the dispatch inputs, not the inspection blob).
        return PaymentWebhookEvent.create("stripe", eventId, eventType,
                null, UUID.randomUUID(), "pi_ext", null);
    }

    @Test
    void staleReceivedRowsAreReDeliveredThroughTheDispatchContractAndMarkedSettled() {
        PaymentWebhookEvent stale = row("evt_stale_1", "payment_intent.succeeded");
        Instant now = Instant.now();
        when(repository.findByProcessingStateAndCreatedAtBefore(
                eq(WebhookProcessingState.RECEIVED), eq(now.minus(WebhookInboxRecovery.STALE_AFTER))))
                .thenReturn(List.of(stale));

        recovery.recoverDue(now);

        // The public proxy-safe entry point carries the row's full contract.
        verify(paymentsService).redeliverWebhookEvent("stripe", "evt_stale_1",
                "payment_intent.succeeded", stale.getPaymentIntentId(), "pi_ext", null);
        // The idempotent backstop always follows a clean re-delivery.
        verify(recorder).markSettled("stripe", "evt_stale_1");
    }

    @Test
    void chargeRefundedRowsReplayTheirStoredSnapshotAmount() {
        PaymentWebhookEvent refunded = PaymentWebhookEvent.create("stripe", "evt_ref_1",
                "charge.refunded", null, UUID.randomUUID(), "pi_ext", 300L);
        Instant now = Instant.now();
        when(repository.findByProcessingStateAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(refunded));

        recovery.recoverDue(now);

        verify(paymentsService).redeliverWebhookEvent("stripe", "evt_ref_1", "charge.refunded",
                refunded.getPaymentIntentId(), "pi_ext", 300L);
        verify(recorder).markSettled("stripe", "evt_ref_1");
    }

    @Test
    void aTransitionConflictLandsFailedWithItsReason() {
        PaymentWebhookEvent stale = row("evt_perm_1", "payment_intent.succeeded");
        when(repository.findByProcessingStateAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(stale));
        org.mockito.Mockito.doThrow(new ConflictException("Cannot transition from SUCCEEDED to SUCCEEDED"))
                .when(paymentsService).redeliverWebhookEvent(anyString(), anyString(), anyString(),
                        any(), anyString(), any());

        recovery.recoverDue(Instant.now());

        verify(recorder).markFailed(eq("stripe"), eq("evt_perm_1"),
                eq("Cannot transition from SUCCEEDED to SUCCEEDED"));
        verify(recorder, never()).markSettled(anyString(), anyString());
        // The row is NEVER deleted — the provider was already answered for
        // this event id; deleting would lose the event permanently.
        verify(recorder, never()).delete(anyString(), anyString());
    }

    @Test
    void aMissingTargetLandsFailedToo() {
        PaymentWebhookEvent stale = row("evt_missing_1", "payment_intent.succeeded");
        when(repository.findByProcessingStateAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(stale));
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Payment intent not found"))
                .when(paymentsService).redeliverWebhookEvent(anyString(), anyString(), anyString(),
                        any(), anyString(), any());

        recovery.recoverDue(Instant.now());

        verify(recorder).markFailed(eq("stripe"), eq("evt_missing_1"),
                eq("Payment intent not found"));
    }

    @Test
    void aTransientFailureKeepsTheRowReceivedForTheNextSweep() {
        PaymentWebhookEvent stale = row("evt_trans_1", "payment_intent.succeeded");
        when(repository.findByProcessingStateAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(stale));
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated transient infrastructure blip"))
                .when(paymentsService).redeliverWebhookEvent(anyString(), anyString(), anyString(),
                        any(), anyString(), any());

        recovery.recoverDue(Instant.now());

        // Neither terminal state is written and the row is never deleted:
        // the next sweep retries it (bounded — one attempt per sweep).
        verify(recorder, never()).markFailed(anyString(), anyString(), anyString());
        verify(recorder, never()).markSettled(anyString(), anyString());
        verify(recorder, never()).delete(anyString(), anyString());
    }

    @Test
    void anEmptySweepTouchesNothing() {
        when(repository.findByProcessingStateAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of());

        recovery.recoverDue(Instant.now());

        verifyNoInteractions(paymentsService);
        verifyNoInteractions(recorder);
    }

    @Test
    void theSweepQueriesWithTheStalenessCutoffDerivedFromNow() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        when(repository.findByProcessingStateAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of());

        recovery.recoverDue(now);

        // The cutoff is now - STALE_AFTER — fresh rows (a delivery possibly
        // still mid-dispatch) are the repository query's responsibility, and
        // the threshold constant is the policy the IT pins end-to-end.
        verify(repository).findByProcessingStateAndCreatedAtBefore(
                WebhookProcessingState.RECEIVED, now.minus(WebhookInboxRecovery.STALE_AFTER));
    }

    @Test
    void nullSnapshotAmountIsForwardedAsNullNotZero() {
        // A charge.refunded row can never exist without its snapshot (the
        // recorder derives it from the verified payload), but the forwarding
        // contract must preserve null-ness for the non-refund event types.
        PaymentWebhookEvent succeeded = row("evt_null_1", "payment_intent.succeeded");
        when(repository.findByProcessingStateAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(succeeded));

        recovery.recoverDue(Instant.now());

        verify(paymentsService).redeliverWebhookEvent(anyString(), anyString(), anyString(),
                any(), anyString(), isNull());
        verify(paymentsService, never()).redeliverWebhookEvent(anyString(), anyString(),
                anyString(), any(), anyString(), anyLong());
    }

    @Test
    void thePolicyConstantsAreTheDocumentedValues() {
        assertThat(WebhookInboxRecovery.STALE_AFTER).as("the staleness threshold (2 minutes —"
                + " exceeds any in-flight dispatch)").isEqualTo(java.time.Duration.ofMinutes(2));
        assertThat(WebhookInboxRecovery.SETTLED_RETENTION).as("the settled retention (7 days —"
                + " symmetric with EventPublicationCleanup, beyond any provider retry horizon)")
                .isEqualTo(java.time.Duration.ofDays(7));
    }
}
