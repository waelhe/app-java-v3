package com.marketplace.orders;

import com.marketplace.orders.spi.OrderCommercePort;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link OrderCommerceAdapter} — the transport-neutral
 * buyer-commerce seam the DGS fetcher delegates to. Every mapping branch the
 * adapter owns gets exercised here with the service and user seam mocked:
 * cart reads/writes, checkout preview (including the null delivery option and
 * the null command), placement, user-scoped reads, history bounds, the
 * fulfillment-selection parse (both the valid method and the rejected one),
 * and the null-tolerant money mapping.
 */
class OrderCommerceAdapterTest {

    private OrdersService orders;
    private CurrentUserProvider currentUsers;
    private OrderCommerceAdapter adapter;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        orders = mock(OrdersService.class);
        currentUsers = mock(CurrentUserProvider.class);
        adapter = new OrderCommerceAdapter(orders, currentUsers);
        authentication = mock(Authentication.class);
    }

    @Test
    void getCartMapsEveryLineField() {
        UUID consumer = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        CartItem item = CartItem.of(UUID.randomUUID(), productId, variantId,
                3, 1250L, "SAR");
        when(currentUsers.getCurrentUserId(authentication)).thenReturn(consumer);
        when(orders.getActiveCartItems(consumer)).thenReturn(List.of(item));

        var lines = adapter.getCart(authentication);

        assertThat(lines).hasSize(1);
        var line = lines.get(0);
        assertThat(line.id()).isEqualTo(item.getId());
        assertThat(line.productId()).isEqualTo(productId);
        assertThat(line.variantId()).isEqualTo(variantId);
        assertThat(line.quantity()).isEqualTo(3);
        assertThat(line.unitAmountMinor()).isEqualTo("1250");
        assertThat(line.currency()).isEqualTo("SAR");
        assertThat(line.lineTotalMinor()).isEqualTo("3750");
    }

    @Test
    void addToCartReturnsTheCreatedLine() {
        UUID consumer = UUID.randomUUID();
        CartItem created = CartItem.of(UUID.randomUUID(), UUID.randomUUID(),
                null, 1, 500L, "SAR");
        when(currentUsers.getCurrentUserId(authentication)).thenReturn(consumer);
        when(orders.addCartItem(consumer, created.getProductId(), null, 1)).thenReturn(created);

        var line = adapter.addToCart(created.getProductId(), null, 1, authentication);

        assertThat(line.id()).isEqualTo(created.getId());
        assertThat(line.quantity()).isEqualTo(1);
        assertThat(line.unitAmountMinor()).isEqualTo("500");
        assertThat(line.lineTotalMinor()).isEqualTo("500");
    }

    @Test
    void removeFromCartDelegatesWithTheResolvedConsumer() {
        UUID consumer = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(currentUsers.getCurrentUserId(authentication)).thenReturn(consumer);

        adapter.removeFromCart(itemId, authentication);

        verify(orders).removeCartItem(consumer, itemId);
    }

    @Test
    void previewCheckoutMapsBreakdownAndTheNullDeliveryOption() {
        UUID consumer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        when(currentUsers.getCurrentUserId(authentication)).thenReturn(consumer);
        var breakdown = new com.marketplace.shared.api.CommercialPricingPort.CommercialBreakdown(
                4000L, 400L, UUID.randomUUID(), "Summer promotion", 100L, UUID.randomUUID(), "SAVE10",
                300L, "SA", true, 750, 3800L);
        when(orders.previewPlacement(eq(consumer), eq("SAVE10"), any())).thenReturn(List.of(
                new OrdersService.PlacementPreview(seller, "SAR", breakdown, null)));

        var previews = adapter.previewCheckout(
                new OrderCommercePort.CheckoutCommand("SAVE10", "PICKUP", null, null, null),
                authentication);

        assertThat(previews).hasSize(1);
        var preview = previews.get(0);
        assertThat(preview.sellerId()).isEqualTo(seller);
        assertThat(preview.subtotalMinor()).isEqualTo("4000");
        assertThat(preview.promotionDiscountMinor()).isEqualTo("400");
        assertThat(preview.promotionName()).isEqualTo("Summer promotion");
        assertThat(preview.voucherDiscountMinor()).isEqualTo("100");
        assertThat(preview.voucherCode()).isEqualTo("SAVE10");
        assertThat(preview.taxMinor()).isEqualTo("300");
        assertThat(preview.taxCountry()).isEqualTo("SA");
        assertThat(preview.taxInclusive()).isTrue();
        assertThat(preview.taxRateBps()).isEqualTo(750);
        assertThat(preview.totalMinor()).isEqualTo("3800");
        assertThat(preview.currency()).isEqualTo("SAR");
        assertThat(preview.deliveryOption()).isNull();
    }

    @Test
    void previewCheckoutWithANullCommandReadsAsNoVoucherAndNoSelection() {
        UUID consumer = UUID.randomUUID();
        when(currentUsers.getCurrentUserId(authentication)).thenReturn(consumer);
        when(orders.previewPlacement(consumer, null, null)).thenReturn(List.of());

        assertThat(adapter.previewCheckout(null, authentication)).isEmpty();

        verify(orders).previewPlacement(consumer, null, null);
    }

    @Test
    void placeOrderMapsThePlacementThroughTheResponseShape() {
        UUID consumer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(currentUsers.getCurrentUserId(authentication)).thenReturn(consumer);
        Order order = orderWithBaseline(orderId);
        OrderItem item = mock(OrderItem.class);
        when(item.getId()).thenReturn(UUID.randomUUID());
        when(item.getProductId()).thenReturn(UUID.randomUUID());
        when(item.getVariantId()).thenReturn(null);
        when(item.getQuantity()).thenReturn(2);
        when(item.getUnitAmountMinor()).thenReturn(1500L);
        when(item.getCurrency()).thenReturn("SAR");
        when(item.lineTotalMinor()).thenReturn(3000L);
        when(orders.place(eq(consumer), eq(null), any())).thenReturn(
                List.of(new OrderDetail(order, List.of(item))));

        var view = adapter.placeOrder(null, authentication);

        assertThat(view.id()).isEqualTo(orderId);
        assertThat(view.status()).isEqualTo("PLACED");
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).lineTotalMinor()).isEqualTo("3000");
        assertThat(view.totalAmountMinor()).isEqualTo("3000");
        assertThat(view.currency()).isEqualTo("SAR");
        assertThat(view.additionalOrders()).isEmpty();
    }

    @Test
    void getOrderMapsTheFrozenDetailForTheUser() {
        UUID orderId = UUID.randomUUID();
        Order order = orderWithBaseline(orderId);
        when(orders.getOrderDetailForUser(orderId, authentication))
                .thenReturn(new OrderDetail(order, List.of()));

        var view = adapter.getOrder(orderId, authentication);

        assertThat(view.id()).isEqualTo(orderId);
        assertThat(view.status()).isEqualTo("PLACED");
    }

    @Test
    void listMyOrdersBoundsTheRequestedLimitAndMapsThePage() {
        UUID consumer = UUID.randomUUID();
        when(currentUsers.getCurrentUserId(authentication)).thenReturn(consumer);
        Order order = orderWithBaseline(UUID.randomUUID());
        when(orders.listByConsumer(eq(consumer), any(Pageable.class), eq(authentication)))
                .thenReturn(new PageImpl<>(List.of(new OrderDetail(order, List.of()))));

        var views = adapter.listMyOrders(500, authentication);

        assertThat(views).hasSize(1);
        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(orders).listByConsumer(eq(consumer), pageable.capture(), eq(authentication));
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void cancelOrderMapsTheCancelledView() {
        UUID orderId = UUID.randomUUID();
        Order order = orderWithBaseline(orderId);
        when(order.getStatus()).thenReturn(OrderStatus.CANCELLED);
        when(order.getCancelledAt()).thenReturn(Instant.parse("2026-10-20T10:30:00Z"));
        when(order.getCancelReason()).thenReturn("buyer request");
        when(orders.cancelForUser(orderId, "buyer request", authentication))
                .thenReturn(new OrderDetail(order, List.of()));

        var view = adapter.cancelOrder(orderId, "buyer request", authentication);

        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.cancelledAt()).isEqualTo(Instant.parse("2026-10-20T10:30:00Z"));
        assertThat(view.cancelReason()).isEqualTo("buyer request");
    }

    @Test
    void requestOrderPaymentIntentDelegatesUntouched() {
        UUID orderId = UUID.randomUUID();
        PaymentIntentDetails details = new PaymentIntentDetails(
                UUID.randomUUID(), null, UUID.randomUUID(), null, "CREATED", "AD",
                3000L, "SAR", orderId, null);
        when(orders.requestPaymentIntent(orderId, authentication)).thenReturn(details);

        assertThat(adapter.requestOrderPaymentIntent(orderId, authentication)).isSameAs(details);
    }

    @Test
    void anUnknownFulfillmentMethodIsRejectedWithTheOperationalMessage() {
        var command = new OrderCommercePort.CheckoutCommand(
                null, "TELEPORT", null, null, null);

        assertThatThrownBy(() -> adapter.placeOrder(command, authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fulfillmentMethod must be PICKUP or PLATFORM_DELIVERY");
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
