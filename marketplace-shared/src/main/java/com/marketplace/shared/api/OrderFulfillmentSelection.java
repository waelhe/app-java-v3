package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The architecture §3.2 Mode B frozen selection — the order's fulfillment
 * agreement as the delivery module reads it after confirmation:
 * <ul>
 *   <li>{@code fulfillmentMethod} — {@code "PICKUP"}, {@code
 *       "PLATFORM_DELIVERY"}, or NULL (the pre-V224 legacy agreement or a
 *       method-less order: the #604 manual add-on delivery path stays
 *       available for exactly these);</li>
 *   <li>the destination snapshot and the frozen shipping amount — present
 *       exactly when the method is PLATFORM_DELIVERY (the V224 shape
 *       law);</li>
 *   <li>{@code activeDeliveryRequestId} — the primary leg's link (the
 *       V218 column), so the quoting path keeps its one-delivery guard.</li>
 * </ul>
 */
public record OrderFulfillmentSelection(
        UUID orderId,
        UUID buyerId,
        UUID sellerId,
        String currency,
        String fulfillmentMethod,
        String shipToAddress,
        Double shipToLatitude,
        Double shipToLongitude,
        long shippingAmountMinor,
        UUID activeDeliveryRequestId) {

    public boolean platformDelivery() {
        return "PLATFORM_DELIVERY".equals(fulfillmentMethod);
    }
}
