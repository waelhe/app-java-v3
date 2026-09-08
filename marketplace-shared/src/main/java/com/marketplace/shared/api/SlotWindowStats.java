package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L25 (feature-expansion roadmap §5): the provider's slot-window aggregates —
 * how many availability slots fall inside the window and how many of them
 * are booked. The occupancy ratio the provider stats derive from is
 * {@code bookedSlots / totalSlots} (0.0 when {@code totalSlots == 0}).
 *
 * <p>Window semantics (the house {@code [from, to)} convention): a slot
 * belongs to the window when its {@code starts_at} satisfies
 * {@code from <= starts_at < to} — the same exclusive end every other window
 * in the codebase uses.
 */
public record SlotWindowStats(long totalSlots, long bookedSlots) {
}
