package com.marketplace.orders;

import com.marketplace.orders.spi.OrderCommercePort;
import com.marketplace.orders.spi.SellerOrderManagementPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Adapter for the existing merchant state machine. It does not create a
 * second fulfillment path: each command invokes the same OrdersService edge
 * used by the current REST seller controller.
 */
@Component
public class SellerOrderManagementAdapter implements SellerOrderManagementPort {

    private static final int MAX_HISTORY = 50;

    private final OrdersService orders;

    public SellerOrderManagementAdapter(OrdersService orders) {
        this.orders = orders;
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public List<OrderCommercePort.OrderView> listSoldOrders(
            int limit, Authentication authentication) {
        int bounded = Math.max(1, Math.min(limit, MAX_HISTORY));
        return orders.listSoldByCaller(authentication, PageRequest.of(0, bounded)).getContent()
                .stream()
                .map(OrderResponses.OrderResponse::of)
                .map(SellerOrderManagementAdapter::toView)
                .toList();
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public OrderCommercePort.OrderView getSoldOrder(
            UUID orderId, Authentication authentication) {
        return toView(OrderResponses.OrderResponse.of(
                orders.getOrderDetailForSeller(orderId, authentication)));
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public OrderCommercePort.OrderView confirmSoldOrder(
            UUID orderId, Authentication authentication) {
        return toView(OrderResponses.OrderResponse.of(
                orders.confirmForSeller(orderId, authentication)));
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public OrderCommercePort.OrderView fulfillSoldOrder(
            UUID orderId, Authentication authentication) {
        return toView(OrderResponses.OrderResponse.of(
                orders.fulfillForSeller(orderId, authentication)));
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public OrderCommercePort.OrderView cancelSoldOrder(
            UUID orderId, String reason, Authentication authentication) {
        return toView(OrderResponses.OrderResponse.of(
                orders.cancelForSeller(orderId, reason, authentication)));
    }

    private static OrderCommercePort.OrderView toView(OrderResponses.OrderResponse response) {
        return new OrderCommercePort.OrderView(response.id(), response.status().name(),
                response.items().stream().map(item -> new OrderCommercePort.OrderLineView(
                        item.id(), item.productId(), item.variantId(), item.quantity(),
                        Long.toString(item.unitAmountMinor()), item.currency(),
                        Long.toString(item.lineTotalMinor()))).toList(),
                Long.toString(response.totalAmountMinor()), toMoney(response.subtotalAmountMinor()),
                toMoney(response.discountAmountMinor()), toMoney(response.taxAmountMinor()),
                response.fulfillmentMethod() == null ? null : response.fulfillmentMethod().name(),
                toMoney(response.shippingAmountMinor()), response.shipToAddress(), response.currency(),
                response.placedAt(), response.confirmedAt(), response.fulfilledAt(),
                response.cancelledAt(), response.cancelReason(), response.deliveryRequestId(),
                response.additionalOrders().stream()
                        .map(SellerOrderManagementAdapter::toView).toList());
    }

    private static String toMoney(Long value) {
        return value == null ? null : Long.toString(value);
    }
}
