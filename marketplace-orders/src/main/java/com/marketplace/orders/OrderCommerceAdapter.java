package com.marketplace.orders;

import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.orders.spi.OrderCommercePort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Implements the transport-neutral commerce port in the owning orders module.
 * Pricing, cart writes, stock reservations, order transitions and privacy
 * gates remain in OrdersService; no JPA entity crosses the shared boundary.
 */
@Component
public class OrderCommerceAdapter implements OrderCommercePort {

    private static final int MAX_ORDER_HISTORY = 50;

    private final OrdersService orders;
    private final CurrentUserProvider currentUsers;

    public OrderCommerceAdapter(OrdersService orders, CurrentUserProvider currentUsers) {
        this.orders = orders;
        this.currentUsers = currentUsers;
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public List<CartLineView> getCart(Authentication authentication) {
        UUID consumerId = currentUsers.getCurrentUserId(authentication);
        return orders.getActiveCartItems(consumerId).stream()
                .map(item -> new CartLineView(item.getId(), item.getProductId(), item.getVariantId(),
                        item.getQuantity(), Long.toString(item.getUnitAmountMinor()), item.getCurrency(),
                        Long.toString(item.lineTotalMinor())))
                .toList();
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public CartLineView addToCart(UUID productId, UUID variantId, int quantity,
                                  Authentication authentication) {
        UUID consumerId = currentUsers.getCurrentUserId(authentication);
        CartItem item = orders.addCartItem(consumerId, productId, variantId, quantity);
        return new CartLineView(item.getId(), item.getProductId(), item.getVariantId(),
                item.getQuantity(), Long.toString(item.getUnitAmountMinor()), item.getCurrency(),
                Long.toString(item.lineTotalMinor()));
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public void removeFromCart(UUID itemId, Authentication authentication) {
        orders.removeCartItem(currentUsers.getCurrentUserId(authentication), itemId);
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public List<CheckoutPreviewView> previewCheckout(CheckoutCommand command,
                                                     Authentication authentication) {
        return orders.previewPlacement(currentUsers.getCurrentUserId(authentication),
                        command == null ? null : command.voucherCode(),
                        toSelection(command))
                .stream()
                .map(preview -> {
                    var breakdown = preview.breakdown();
                    var option = preview.deliveryOption();
                    return new CheckoutPreviewView(preview.sellerId(),
                            Long.toString(breakdown.subtotalMinor()),
                            Long.toString(breakdown.promotionDiscountMinor()),
                            breakdown.promotionName(),
                            Long.toString(breakdown.voucherDiscountMinor()),
                            breakdown.voucherCode(),
                            Long.toString(breakdown.taxMinor()),
                            breakdown.taxCountry(),
                            breakdown.taxInclusive(),
                            breakdown.taxRateBps(),
                            Long.toString(breakdown.totalMinor()),
                            preview.currency(),
                            option == null ? null : new DeliveryOptionView(option.serviceable(),
                                    Long.toString(option.feeMinor()), option.etaSeconds(),
                                    option.currency(), option.refusal()));
                })
                .toList();
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public OrderView placeOrder(CheckoutCommand command, Authentication authentication) {
        var placement = orders.place(currentUsers.getCurrentUserId(authentication),
                command == null ? null : command.voucherCode(), toSelection(command));
        return toView(OrderResponses.OrderResponse.ofPlacement(placement));
    }

    @Override
    public OrderView getOrder(UUID orderId, Authentication authentication) {
        return toView(OrderResponses.OrderResponse.of(orders.getOrderDetailForUser(orderId, authentication)));
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public List<OrderView> listMyOrders(int limit, Authentication authentication) {
        int bounded = Math.max(1, Math.min(limit, MAX_ORDER_HISTORY));
        UUID consumerId = currentUsers.getCurrentUserId(authentication);
        var page = orders.listByConsumer(consumerId,
                PageRequest.of(0, bounded, Sort.by(Sort.Direction.DESC, "placedAt")
                        .and(Sort.by(Sort.Direction.DESC, "id"))), authentication);
        return page.getContent().stream().map(OrderResponses.OrderResponse::of)
                .map(OrderCommerceAdapter::toView).toList();
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public OrderView cancelOrder(UUID orderId, String reason, Authentication authentication) {
        return toView(OrderResponses.OrderResponse.of(orders.cancelForUser(orderId, reason, authentication)));
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntentDetails requestOrderPaymentIntent(UUID orderId, Authentication authentication) {
        return orders.requestPaymentIntent(orderId, authentication);
    }

    private static OrdersService.FulfillmentSelection toSelection(CheckoutCommand command) {
        if (command == null || command.fulfillmentMethod() == null || command.fulfillmentMethod().isBlank()) {
            return null;
        }
        FulfillmentMethod method;
        try {
            method = FulfillmentMethod.valueOf(command.fulfillmentMethod());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("fulfillmentMethod must be PICKUP or PLATFORM_DELIVERY",
                    exception);
        }
        return new OrdersService.FulfillmentSelection(method, command.destinationAddress(),
                command.destinationLatitude(), command.destinationLongitude());
    }

    private static OrderView toView(OrderResponses.OrderResponse response) {
        return new OrderView(response.id(), response.status().name(),
                response.items().stream().map(item -> new OrderLineView(item.id(), item.productId(),
                        item.variantId(), item.quantity(), Long.toString(item.unitAmountMinor()),
                        item.currency(), Long.toString(item.lineTotalMinor()))).toList(),
                Long.toString(response.totalAmountMinor()),
                toMoney(response.subtotalAmountMinor()), toMoney(response.discountAmountMinor()),
                toMoney(response.taxAmountMinor()),
                response.fulfillmentMethod() == null ? null : response.fulfillmentMethod().name(),
                toMoney(response.shippingAmountMinor()), response.shipToAddress(), response.currency(),
                response.placedAt(), response.confirmedAt(), response.fulfilledAt(), response.cancelledAt(),
                response.cancelReason(), response.deliveryRequestId(),
                response.additionalOrders().stream().map(OrderCommerceAdapter::toView).toList());
    }

    private static String toMoney(Long value) {
        return value == null ? null : Long.toString(value);
    }
}
