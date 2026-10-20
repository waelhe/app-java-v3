package com.marketplace.orders;

import com.marketplace.orders.spi.OrderCommercePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link SellerOrderManagementAdapter} — the seller-side
 * workflow seam the DGS fetcher delegates to. Every command maps through the
 * same OrdersService edges the REST seller controller uses; these pins prove
 * the mapping and the history bounds without a second state machine.
 */
class SellerOrderManagementAdapterTest {

    private OrdersService orders;
    private SellerOrderManagementAdapter adapter;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        orders = mock(OrdersService.class);
        adapter = new SellerOrderManagementAdapter(orders);
        authentication = mock(Authentication.class);
    }

    @Test
    void listSoldOrdersBoundsTheLimitAndMapsThePage() {
        UUID orderId = UUID.randomUUID();
        OrderDetail detail = new OrderDetail(orderWithBaseline(orderId), List.of());
        when(orders.listSoldByCaller(eq(authentication), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(detail)));

        var views = adapter.listSoldOrders(500, authentication);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).id()).isEqualTo(orderId);
        assertThat(views.get(0).status()).isEqualTo("PLACED");
        var pageable = forClass(Pageable.class);
        verify(orders).listSoldByCaller(eq(authentication), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void getSoldOrderMapsTheFrozenDetail() {
        UUID orderId = UUID.randomUUID();
        OrderDetail detail = new OrderDetail(orderWithBaseline(orderId), List.of());
        when(orders.getOrderDetailForSeller(orderId, authentication)).thenReturn(detail);

        var view = adapter.getSoldOrder(orderId, authentication);

        assertThat(view.id()).isEqualTo(orderId);
        assertThat(view.currency()).isEqualTo("SAR");
    }

    @Test
    void confirmSoldOrderMapsTheConfirmedState() {
        UUID orderId = UUID.randomUUID();
        Order order = orderWithBaseline(orderId);
        when(order.getStatus()).thenReturn(OrderStatus.CONFIRMED);
        when(order.getConfirmedAt()).thenReturn(Instant.parse("2026-10-20T11:00:00Z"));
        OrderDetail detail = new OrderDetail(order, List.of());
        when(orders.confirmForSeller(orderId, authentication)).thenReturn(detail);

        var view = adapter.confirmSoldOrder(orderId, authentication);

        assertThat(view.status()).isEqualTo("CONFIRMED");
        assertThat(view.confirmedAt()).isEqualTo(Instant.parse("2026-10-20T11:00:00Z"));
    }

    @Test
    void fulfillSoldOrderMapsTheFulfilledState() {
        UUID orderId = UUID.randomUUID();
        Order order = orderWithBaseline(orderId);
        when(order.getStatus()).thenReturn(OrderStatus.FULFILLED);
        when(order.getFulfilledAt()).thenReturn(Instant.parse("2026-10-20T12:00:00Z"));
        OrderDetail detail = new OrderDetail(order, List.of());
        when(orders.fulfillForSeller(orderId, authentication)).thenReturn(detail);

        var view = adapter.fulfillSoldOrder(orderId, authentication);

        assertThat(view.status()).isEqualTo("FULFILLED");
        assertThat(view.fulfilledAt()).isEqualTo(Instant.parse("2026-10-20T12:00:00Z"));
    }

    @Test
    void cancelSoldOrderMapsTheCancelledStateAndItsReason() {
        UUID orderId = UUID.randomUUID();
        Order order = orderWithBaseline(orderId);
        when(order.getStatus()).thenReturn(OrderStatus.CANCELLED);
        when(order.getCancelledAt()).thenReturn(Instant.parse("2026-10-20T13:00:00Z"));
        when(order.getCancelReason()).thenReturn("out of stock");
        OrderDetail detail = new OrderDetail(order, List.of());
        when(orders.cancelForSeller(orderId, "out of stock", authentication)).thenReturn(detail);

        var view = adapter.cancelSoldOrder(orderId, "out of stock", authentication);

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.cancelReason()).isEqualTo("out of stock");
    }

    /** A minimal order with the getters the response mapping reads. */
    private static Order orderWithBaseline(UUID orderId) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(orderId);
        when(order.getStatus()).thenReturn(OrderStatus.PLACED);
        when(order.getTotalAmountMinor()).thenReturn(3000L);
        when(order.getSubtotalAmountMinor()).thenReturn(3300L);
        when(order.getDiscountAmountMinor()).thenReturn(300L);
        when(order.getTaxAmountMinor()).thenReturn(0L);
        when(order.getFulfillmentMethod()).thenReturn(null);
        when(order.getShippingAmountMinor()).thenReturn(null);
        when(order.getShipToAddress()).thenReturn(null);
        when(order.getCurrency()).thenReturn("SAR");
        when(order.getPlacedAt()).thenReturn(Instant.parse("2026-10-20T10:00:00Z"));
        when(order.getConfirmedAt()).thenReturn(null);
        when(order.getFulfilledAt()).thenReturn(null);
        when(order.getCancelledAt()).thenReturn(null);
        when(order.getCancelReason()).thenReturn(null);
        when(order.getDeliveryRequestId()).thenReturn(null);
        return order;
    }
}
