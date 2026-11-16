package com.marketplace.shared.api;

import java.util.UUID;

/**
 * A-11 (compliance plan wave C: C.1 — the orders state machine's
 * cross-boundary events; parallel contracts ledger §1 additive-only
 * registration). Carries the buyer's user id so cross-module consumers
 * (notifications — the late-lander listener recorded in the ledger) never
 * need a dependency on the orders module to act: the payload IS the
 * interface (the ledger's placement rule — cross-boundary events live in
 * shared/api records).
 */
public record OrderConfirmedEvent(UUID orderId, UUID consumerId) {
}
