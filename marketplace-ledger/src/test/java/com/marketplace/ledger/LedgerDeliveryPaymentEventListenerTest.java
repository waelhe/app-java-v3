package com.marketplace.ledger;

import com.marketplace.shared.api.DeliveryCompletedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A1 (adversarial review 2026-11-10, owner-gate batch 6a — the ADR-0013
 * D4 execution): the delivery-completion ROUTING proofs. The pre-fix
 * listener routed every completion into {@code recognize}, whose first
 * guard throws on the order-leg's by-design {@code paymentIntentId = null}
 * — the publication failed FOREVER on every order-leg completion (the S12
 * lesson) and the courier's compensation was never booked anywhere (the
 * ledger's own finding A1).
 */
class LedgerDeliveryPaymentEventListenerTest {

    private final LedgerJournalService journal = mock(LedgerJournalService.class);
    private final PaymentIntentLookupPort lookup = mock(PaymentIntentLookupPort.class);

    private final LedgerDeliveryPaymentEventListener listener =
            new LedgerDeliveryPaymentEventListener(journal, lookup);

    private DeliveryCompletedEvent orderLegEvent(UUID deliveryRequestId, UUID orderId,
                                                long fare, long compensation, UUID courier) {
        return new DeliveryCompletedEvent(deliveryRequestId, null, fare, "SAR",
                compensation, Instant.now(), courier, orderId);
    }

    @Test
    void orderLegCompletionBindsTheOfficialIntentAndBooksTheCarrierCompensation() {
        // A1 + the recommendation's event binding: the order-leg (paymentIntentId
        // null BY DESIGN) resolves the ORDER's official intent and books the
        // courier's compensation pair — never the recognize chain whose
        // delivery-owned capture the order-leg never had.
        UUID deliveryRequestId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID officialIntentId = UUID.randomUUID();
        UUID courier = UUID.randomUUID();
        var event = orderLegEvent(deliveryRequestId, orderId, 4000L, 3000L, courier);
        var orderIntent = new PaymentIntentDetails(officialIntentId, null, UUID.randomUUID(), null,
                "SUCCEEDED", "ORDER", 17400L, "SAR", orderId, null, null);

        when(lookup.findCurrentByOrderId(orderId)).thenReturn(Optional.of(orderIntent));

        listener.onDeliveryCompleted(event);

        verify(journal).compensateCarrier(event, officialIntentId);
        verify(journal, never()).recognize(any());
    }

    @Test
    void directDeliveryCompletionKeepsTheRecognizeChainUntouched() {
        // The direct leg (its OWN payment intent): the capture → recognize
        // chain runs exactly as before — A1 added no friction to it.
        UUID deliveryRequestId = UUID.randomUUID();
        UUID directIntentId = UUID.randomUUID();
        UUID courier = UUID.randomUUID();
        var event = new DeliveryCompletedEvent(deliveryRequestId, directIntentId, 4000L, "SAR",
                3000L, Instant.now(), courier, null);

        listener.onDeliveryCompleted(event);

        verify(journal).recognize(event);
        verify(journal, never()).compensateCarrier(any(), any());
        verifyNoInteractions(lookup);
    }

    @Test
    void orderLegCompletionWithoutAnIntentFailsClosedExplicitly() {
        // The real-inconsistency refusal: a delivered order-leg with no
        // payment intent (a delivered-but-unpaid order — the dispatch gate
        // requires settled payment). The explicit exception keeps the
        // publication recoverable and monitoring sees it — the S12
        // discipline applied to a genuinely inconsistent state, never a
        // legal event.
        UUID deliveryRequestId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var event = orderLegEvent(deliveryRequestId, orderId, 4000L, 3000L, UUID.randomUUID());

        when(lookup.findCurrentByOrderId(orderId)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> listener.onDeliveryCompleted(event));

        verify(journal, never()).compensateCarrier(any(), any());
        verify(journal, never()).recognize(any());
    }
}
