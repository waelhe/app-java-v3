package com.marketplace.shared.api;

import java.util.UUID;

/** Buyer-authorized, already-confirmed commerce order snapshot for delivery creation. */
public record OrderDeliveryDetails(
        UUID orderId,
        UUID buyerId,
        UUID sellerId,
        String currency,
        UUID activeDeliveryRequestId) { }
