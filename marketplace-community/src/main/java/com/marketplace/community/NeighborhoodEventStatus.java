package com.marketplace.community;

/**
 * JT-20 (#536 discovery waves D1-D4 — the events leg): a neighborhood
 * event's honest gathering state. The DB CHECK (V174) pins the SQL side
 * (D-N7's two-sided discipline: this enum mirrors that membership
 * exactly).
 *
 * <ul>
 *   <li>{@code ACTIVE} — the gathering is on as planned. The birth state
 *       (the V174 DEFAULT backfills every live row).</li>
 *   <li>{@code CANCELLED} — the gathering will not happen. The row, its
 *       seats and its audit trail stay (the state is a fact, not an
 *       erasure); the board's upcoming read excludes it, so a cancelled
 *       event never masquerades as upcoming.</li>
 *   <li>{@code POSTPONED} — the gathering is delayed to a not-yet-stated
 *       time. The same honest exclusion from the upcoming board — the
 *       new time rides a fresh honest row or the organizer's update when
 *       that surface arrives.</li>
 * </ul>
 *
 * <p><b>The flip surface is a documented reservation</b> (the V83
 * {@code featured} precedent verbatim): this wave pins the column, the
 * vocabulary, the status-honest reads and the response projection; the
 * organizer's cancel/postpone command is a product decision that needs
 * its own gate design — surfacing it silently is exactly what this house
 * never does. Until then no code path writes anything but
 * {@code ACTIVE} (the V42 {@code status} column's own discipline).
 */
public enum NeighborhoodEventStatus {
    ACTIVE,
    CANCELLED,
    POSTPONED
}
