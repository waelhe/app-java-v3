package com.marketplace.orders;

/**
 * A-11 (compliance plan wave C: C.1) — the cart lifecycle. {@code ACTIVE}
 * is the mutable shopping state; {@code CHECKED_OUT} is the tombstone a
 * placement leaves behind (the partial-unique index in V113 guarantees at
 * most one ACTIVE cart per consumer, so the next shopping session starts a
 * fresh cart — exactly one live draft per buyer by construction).
 */
public enum CartStatus {
    ACTIVE,
    CHECKED_OUT
}
