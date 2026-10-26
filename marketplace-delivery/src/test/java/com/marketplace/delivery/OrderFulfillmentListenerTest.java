package com.marketplace.delivery;

import java.util.UUID;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.OrderConfirmedEvent;
import com.marketplace.shared.api.OrderFulfillmentSelection;
import com.marketplace.shared.api.ResourceNotFoundException;
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
 * confirmation edge's payment gate — the checkout delivery leg requires
 * VERIFIED payment for the order's frozen payable, never the seller's
 * bare confirmation. The unpaid skip hands the leg to the settlement
 * edge (DeliveryPaymentSettlementListener); the paid confirmation
 * creates it here as before.
 */
@ExtendWith(MockitoExtension.class)
class OrderFulfillmentListenerTest {

    @Mock
    private DeliveryService deliveryService;

    @Mock
    private com.marketplace.shared.api.OrderDeliveryPort orders;

    @Mock
    private com.marketplace.shared.api.PaymentIntentLookupPort payments;

    @InjectMocks
    private OrderFulfillmentListener listener;

    private OrderFulfillmentSelection platformDeliverySelection(UUID orderId, UUID buyerId) {
        return new OrderFulfillmentSelection(orderId, buyerId, UUID.randomUUID(), "SAR",
                "PLATFORM_DELIVERY", "Riyadh", 24.7, 46.7, 2500L, null);
    }

    @Test
    void aPaidConfirmationCreatesTheCheckoutLeg() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        OrderFulfillmentSelection selection = platformDeliverySelection(orderId, buyerId);
        when(orders.requireOrderFulfillment(orderId, buyerId)).thenReturn(selection);
        when(payments.isOrderPayableSettled(orderId)).thenReturn(true);

        listener.onOrderConfirmed(new OrderConfirmedEvent(orderId, buyerId));

        verify(deliveryService).createOrderLegFromFulfillment(selection);
    }

    @Test
    void anUnpaidConfirmationNeverCreatesADispatchReadyLeg() {
        // Row 41's exact attack: a seller confirms an UNPAID order — the
        // leg (and the courier capacity it reserves) must wait for the
        // verified payment; the settlement edge owns the creation.
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        OrderFulfillmentSelection selection = platformDeliverySelection(orderId, buyerId);
        when(orders.requireOrderFulfillment(orderId, buyerId)).thenReturn(selection);
        when(payments.isOrderPayableSettled(orderId)).thenReturn(false);

        listener.onOrderConfirmed(new OrderConfirmedEvent(orderId, buyerId));

        verify(deliveryService, never()).createOrderLegFromFulfillment(any());
    }

    @Test
    void aNonPlatformDeliverySelectionIsAnHonestNoOp() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        when(orders.requireOrderFulfillment(orderId, buyerId)).thenReturn(
                new OrderFulfillmentSelection(orderId, buyerId, UUID.randomUUID(), "SAR",
                        "PICKUP", null, null, null, 0L, null));

        listener.onOrderConfirmed(new OrderConfirmedEvent(orderId, buyerId));

        verifyNoInteractions(deliveryService, payments);
    }

    @Test
    void aRedeliveredEventPastConfirmedIsTheRecoverableNoOp() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        when(orders.requireOrderFulfillment(orderId, buyerId))
                .thenThrow(new ConflictException("Only a confirmed order with a known seller can be delivered"));

        listener.onOrderConfirmed(new OrderConfirmedEvent(orderId, buyerId));

        verifyNoInteractions(deliveryService, payments);
    }

    @Test
    void aVanishedOrderIsTheRecoverableNoOp() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        when(orders.requireOrderFulfillment(orderId, buyerId))
                .thenThrow(new ResourceNotFoundException("Order", orderId));

        listener.onOrderConfirmed(new OrderConfirmedEvent(orderId, buyerId));

        verifyNoInteractions(deliveryService, payments);
    }
}
