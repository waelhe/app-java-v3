package com.marketplace.shared.api;

import java.util.UUID;

/**
 * A-11 (compliance plan wave C: C.1 — the orders state machine's
 * cross-boundary events; parallel contracts ledger §1 additive-only
 * registration). Payload shape mirrors {@link OrderConfirmedEvent}: the ids
 * a consumer needs, nothing more.
 */
public record OrderFulfilledEvent(UUID orderId, UUID consumerId) {
}
