package com.marketplace.orders;

/**
 * A-11 (compliance plan wave C: C.1) — the order state machine. The legal
 * transitions (guarded in {@code OrdersService}, each with its event):
 *
 * <pre>
 *            place (from cart)          confirm                fulfill
 *   [cart] ───────────────────▶ PLACED ──────────▶ CONFIRMED ──────────▶ FULFILLED
 *                                 │                   │
 *                                 │ cancel            │ cancel
 *                                 ▼                   ▼
 *                              CANCELLED ◀────────────┘
 * </pre>
 *
 * <p>{@code FULFILLED} and {@code CANCELLED} are terminal: the guards
 * answer {@code ConflictException} for any transition out of them — the
 * machine's invariants are service-level, DB-level (the V113 status CHECK
 * pins the membership set), and audit-level (Envers revisions per
 * transition).
 *
 * <p><b>The event discipline (the A-03 measured lesson
 * institutionalized):</b> a transition publishes a cross-boundary event only
 * where a cross-boundary consumer exists today — CONFIRMED, FULFILLED and
 * CANCELLED publish (notifications consumes all three, the ledger records
 * the late-lander crossing); PLACED publishes nothing: it is the buyer's
 * own intra-module state change, and an event with no listener is a
 * measured defect, not forward compatibility.
 */
public enum OrderStatus {
    PLACED,
    CONFIRMED,
    FULFILLED,
    CANCELLED
}
