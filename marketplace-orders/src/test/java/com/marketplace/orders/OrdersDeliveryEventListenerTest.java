package com.marketplace.orders;

import java.time.Instant;
import java.util.UUID;

import com.marketplace.shared.api.DeliveryCompletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The delivery handoff's listening half (the marketplace fulfillment
 * contract): a completed DELIVERY event carries the physical proof the
 * order's terminal fulfillment rides — but only when the event names an
 * order (the DIRECT delivery mode's events never do; the order module
 * is not their consumer).
 */
@ExtendWith(MockitoExtension.class)
class OrdersDeliveryEventListenerTest {

    @Mock
    private OrdersService orders;

    @InjectMocks
    private OrdersDeliveryEventListener listener;

    @Test
    void aLinkedDeliveryCompletionFulfillsTheOrder() {
        UUID orderId = UUID.randomUUID();
        UUID deliveryRequestId = UUID.randomUUID();

        listener.onDeliveryCompleted(new DeliveryCompletedEvent(deliveryRequestId, null,
                5000L, "SAR", 0L, Instant.now(), null, orderId));

        verify(orders).fulfillFromDelivery(orderId, deliveryRequestId);
    }

    @Test
    void aDirectDeliveryCompletionNamesNoOrderAndTouchesNothing() {
        // The DIRECT mode's completion (a standalone delivery with no
        // marketplace order behind it) must never reach the order machine.
        listener.onDeliveryCompleted(new DeliveryCompletedEvent(UUID.randomUUID(), null,
                5000L, "SAR", 0L, Instant.now(), null, null));

        verifyNoInteractions(orders);
    }
}
