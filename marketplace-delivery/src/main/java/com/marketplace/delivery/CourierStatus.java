package com.marketplace.delivery;

/**
 * The V226 courier OPERATIONAL lifecycle (the dispatcher's verdict):
 * ACTIVE participates in dispatch eligibility; SUSPENDED disappears from
 * it (the assignment gate and the availability write both refuse). The
 * courier's own availability flag stays a LIVE signal — status is the
 * operator's standing decision.
 */
public enum CourierStatus {
    ACTIVE,
    SUSPENDED
}
