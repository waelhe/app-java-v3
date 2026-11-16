package com.marketplace.delivery;

import java.util.Optional;
import java.util.UUID;

import com.marketplace.shared.api.OrderFulfillmentSelection;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent findings 2026-10-26T13:11 export, row 41 (CWE-841): the
 * settlement edge — the second half of the checkout leg's payment
 * proof. When the order's frozen payable settles (the payment's
 * COMPLETED machine event), the seller-first confirmed order (whose
 * confirmation edge skipped the unpaid state) gets its leg HERE — from
 * the same frozen selection, through the same idempotent service. Every
 * other shape is the honest no-op.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryPaymentSettlementListenerTest {

    @Mock
    private DeliveryService deliveryService;

    @Mock
    private com.marketplace.shared.api.OrderDeliveryPort orders;

    @InjectMocks
    private DeliveryPaymentSettlementListener listener;

    private OrderFulfillmentSelection platformDeliverySelection(UUID orderId, UUID buyerId) {
        return new OrderFulfillmentSelection(orderId, buyerId, UUID.randomUUID(), "SAR",
                "PLATFORM_DELIVERY", "Riyadh", 24.7, 46.7, 2500L, null);
    }

    @Test
    void aSettledOrderPayableCreatesTheLegForTheSellerFirstConfirmedOrder() {
        // Row 41's recovery flow: the seller confirmed while unpaid (the
        // confirmation edge skipped), the payment lands NOW — the leg is
        // created from the frozen selection, verified payment by
        // construction (this edge only fires on COMPLETED).
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        OrderFulfillmentSelection selection = platformDeliverySelection(orderId, buyerId);
        when(orders.findOrderFulfillmentByPaymentIntent(paymentIntentId))
                .thenReturn(Optional.of(selection));

        listener.onPaymentStateChanged(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED"));

        verify(deliveryService).createOrderLegFromFulfillment(selection);
    }

    @Test
    void aNonCompletedStateNeverReachesTheOrderResolution() {
        listener.onPaymentStateChanged(new PaymentStateChangedEvent(UUID.randomUUID(), "FAILED"));
        listener.onPaymentStateChanged(new PaymentStateChangedEvent(UUID.randomUUID(), "INITIATED"));
        listener.onPaymentStateChanged(new PaymentStateChangedEvent(UUID.randomUUID(), "REFUNDED"));

        verifyNoInteractions(orders, deliveryService);
    }

    @Test
    void aNonOrderIntentIsTheHonestNoOp() {
        // A booking/ad/loan/delivery intent settles — no order behind it:
        // the port's absent answer covers it, nothing is created.
        UUID paymentIntentId = UUID.randomUUID();
        when(orders.findOrderFulfillmentByPaymentIntent(paymentIntentId))
                .thenReturn(Optional.empty());

        listener.onPaymentStateChanged(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED"));

        verify(deliveryService, never()).createOrderLegFromFulfillment(any());
    }

    @Test
    void anOrderWithoutAPlatformDeliverySelectionIsTheHonestNoOp() {
        UUID paymentIntentId = UUID.randomUUID();
        when(orders.findOrderFulfillmentByPaymentIntent(paymentIntentId)).thenReturn(
                Optional.of(new OrderFulfillmentSelection(UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "SAR", "PICKUP", null, null, null, 0L, null)));

        listener.onPaymentStateChanged(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED"));

        verify(deliveryService, never()).createOrderLegFromFulfillment(any());
    }
}
