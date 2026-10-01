package com.marketplace.community;

/**
 * L49 (the Nextdoor-2026 completeness wave — gap #4, the events layer):
 * a neighborhood event's category — the events board's one filter axis
 * (the Specifications {@code hasCategory} rides this enum; the V83 DB
 * CHECK pins the SQL side per D-N7).
 *
 * <p>The vocabulary is the PRODUCT's own — the frontend contract
 * {@code src/lib/neighborhood-events.ts} {@code EVENT_CATEGORIES}
 * measured verbatim (the design's five filter chips), so the Java enum,
 * the DB CHECK and the product surface share one membership with zero
 * translation: {@code SPORTS_FAMILY} و{@code VOLUNTEER} و{@code SOCIAL}
 * و{@code MARKET} و{@code WORKSHOP}.
 *
 * <p>Widening this vocabulary later is the L43 sequence again: one
 * CHECK-widening migration plus this enum's one constant, nothing else
 * — the board's reads and filters ride the same axis untouched.
 */
public enum EventCategory {
    SPORTS_FAMILY,
    VOLUNTEER,
    SOCIAL,
    MARKET,
    WORKSHOP
}
