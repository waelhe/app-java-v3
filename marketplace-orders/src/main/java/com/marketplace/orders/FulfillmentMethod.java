package com.marketplace.orders;

/**
 * The V224 checkout fulfillment methods (the closed DB vocabulary): PICKUP
 * (the buyer collects) and PLATFORM_DELIVERY (the platform's own courier
 * fleet — the ADR-0013 D4 launch mode). NULL on the column = the pre-V224
 * legacy agreement (no selection recorded; the #604 manual add-on delivery
 * path remains available for exactly these orders).
 */
public enum FulfillmentMethod {
    PICKUP,
    PLATFORM_DELIVERY
}
