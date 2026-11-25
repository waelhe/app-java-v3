package com.marketplace.orders;

import java.lang.reflect.Method;
import java.util.UUID;

import com.marketplace.orders.spi.OrdersContentPurgeAdapter;
import com.marketplace.shared.api.OrderCancelledEvent;
import com.marketplace.shared.api.OrderFulfilledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.modulith.events.ApplicationModuleListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * The terminal-completion leg's wiring tests (the 2026-11-23 #808
 * review's third finding): the module's own terminal events drive the
 * ONE-order targeted purge the moment the transition commits — the
 * listener is a thin delegation (the SavedSearchEventListener pattern),
 * so the pins are the delegation itself, the failure propagation (the
 * at-least-once law: a purge failure marks the publication FAILED for
 * the registry's resubmission — never swallowed, never touching the
 * order's own committed transition), and the
 * {@code @ApplicationModuleListener} contract on both handlers
 * (AFTER_COMMIT + REQUIRES_NEW — the annotation's own semantics, the
 * same pin the booking/payment listener tests carry).
 */
@ExtendWith(MockitoExtension.class)
class OrdersTerminalPurgeListenerTest {

    @Mock
    private OrdersContentPurgeAdapter purgeAdapter;

    private OrdersTerminalPurgeListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrdersTerminalPurgeListener(purgeAdapter);
    }

    @Test
    void theFulfilledEventDrivesTheTargetedPurgeForThatOrder() {
        UUID orderId = UUID.randomUUID();
        listener.onOrderFulfilled(new OrderFulfilledEvent(orderId, UUID.randomUUID()));
        verify(purgeAdapter).purgeTerminalOrderAddress(orderId);
    }

    @Test
    void theCancelledEventDrivesTheTargetedPurgeForThatOrder() {
        UUID orderId = UUID.randomUUID();
        listener.onOrderCancelled(new OrderCancelledEvent(orderId, UUID.randomUUID(), "late"));
        verify(purgeAdapter).purgeTerminalOrderAddress(orderId);
    }

    @Test
    void aPurgeFailurePropagatesForTheRegistrysResubmission() {
        // Never swallowed: the failed publication turns FAILED and the
        // standing resubmission retries it — the order's own committed
        // transition is already past (AFTER_COMMIT), so the retry is the
        // only recovery path and the exception MUST reach the registry.
        UUID orderId = UUID.randomUUID();
        doThrow(new IllegalStateException("injected purge failure"))
                .when(purgeAdapter).purgeTerminalOrderAddress(orderId);
        assertThatThrownBy(() ->
                listener.onOrderFulfilled(new OrderFulfilledEvent(orderId, UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected purge failure");
    }

    @Test
    void bothHandlersCarryTheApplicationModuleListenerContract() {
        // AFTER_COMMIT + REQUIRES_NEW — the annotation's own semantics;
        // pinned so a future edit cannot silently drop the listener onto
        // the publishing transaction.
        for (Method m : OrdersTerminalPurgeListener.class.getDeclaredMethods()) {
            if (m.getName().startsWith("on")) {
                assertThat(m.isAnnotationPresent(ApplicationModuleListener.class))
                        .as("method %s must carry @ApplicationModuleListener", m.getName())
                        .isTrue();
            }
        }
    }
}
