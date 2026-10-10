package com.marketplace.shared.api;

import java.util.UUID;

/**
 * A-11 (compliance plan wave C: C.1 — the orders state machine's
 * cross-boundary events; parallel contracts ledger §1 additive-only
 * registration). {@code reason} rides the payload so consumers can inform
 * the buyer without re-querying the orders module.
 */
public record OrderCancelledEvent(UUID orderId, UUID consumerId, String reason) {
}
