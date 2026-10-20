package com.marketplace.shared.api;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Buyer-commerce transport boundary. The orders module remains authoritative
 * for cart ownership, pricing, stock reservation, checkout, order transitions,
 * payment-intent linkage and order privacy.
 */
@NamedInterface("order-commerce")
public interface OrderCommercePort {

    List<CartLineView> getCart(Authentication authentication);
    CartLineView addToCart(UUID productId, UUID variantId, int quantity, Authentication authentication);
    void removeFromCart(UUID itemId, Authentication authentication);
    List<CheckoutPreviewView> previewCheckout(CheckoutCommand command, Authentication authentication);
    OrderView placeOrder(CheckoutCommand command, Authentication authentication);
    OrderView getOrder(UUID orderId, Authentication authentication);
    List<OrderView> listMyOrders(int limit, Authentication authentication);
    OrderView cancelOrder(UUID orderId, String reason, Authentication authentication);
    PaymentIntentDetails requestOrderPaymentIntent(UUID orderId, Authentication authentication);

    record CheckoutCommand(
            String voucherCode,
            String fulfillmentMethod,
            String destinationAddress,
            Double destinationLatitude,
            Double destinationLongitude) { }

    record CartLineView(
            UUID id, UUID productId, UUID variantId, int quantity,
            String unitAmountMinor, String currency, String lineTotalMinor) { }

    record DeliveryOptionView(
            boolean serviceable, String feeMinor, long etaSeconds, String currency, String refusal) { }

    record CheckoutPreviewView(
            UUID sellerId,
            String subtotalMinor,
            String promotionDiscountMinor,
            String promotionName,
            String voucherDiscountMinor,
            String voucherCode,
            String taxMinor,
            String taxCountry,
            boolean taxInclusive,
            int taxRateBps,
            String totalMinor,
            String currency,
            DeliveryOptionView deliveryOption) { }

    record OrderLineView(
            UUID id, UUID productId, UUID variantId, int quantity,
            String unitAmountMinor, String currency, String lineTotalMinor) { }

    record OrderView(
            UUID id,
            String status,
            List<OrderLineView> items,
            String totalAmountMinor,
            String subtotalAmountMinor,
            String discountAmountMinor,
            String taxAmountMinor,
            String fulfillmentMethod,
            String shippingAmountMinor,
            String shipToAddress,
            String currency,
            Instant placedAt,
            Instant confirmedAt,
            Instant fulfilledAt,
            Instant cancelledAt,
            String cancelReason,
            UUID deliveryRequestId,
            List<OrderView> additionalOrders) { }
}
