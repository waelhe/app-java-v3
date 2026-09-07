package com.marketplace.shared.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Search criteria for the catalog search port.
 *
 * <p>L27 (feature-expansion roadmap §5) adds the stay-window criteria
 * {@code checkIn}/{@code checkOut} with the interval convention
 * {@code [checkIn, checkOut)} — exclusive end, the same convention the
 * availability overlap predicate uses ({@code AvailabilityService.isAvailable},
 * strict inequalities {@code slot.starts_at < checkOut} and
 * {@code slot.ends_at > checkIn}). The record is its own gate: an invalid
 * window cannot exist — construction itself rejects a half-open window or a
 * non-positive one ({@link BadRequestException} → HTTP 400 through
 * {@code GlobalExceptionHandler}), before any query runs (the acceptance
 * criterion "validate inputs before the predicate").
 */
public record SearchCriteria(
        String query,
        String category,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Instant checkIn,
        Instant checkOut
) {

    /**
     * Canonical constructor — the window invariant:
     * both dates together, and {@code checkIn < checkOut} strictly
     * (a zero-length or reversed window is a 400, not a silently-empty page).
     */
    public SearchCriteria {
        if (checkIn == null && checkOut == null) {
            // no window — the legacy four-criteria form, unchanged
        } else if (checkIn == null || checkOut == null) {
            throw new BadRequestException("checkIn and checkOut must be provided together");
        } else if (!checkIn.isBefore(checkOut)) {
            throw new BadRequestException("checkIn must be strictly before checkOut");
        }
    }

    /**
     * Legacy four-component form (pre-L27 callers): no stay window.
     * Kept so every existing construction site compiles unchanged.
     */
    public SearchCriteria(String query, String category, BigDecimal minPrice, BigDecimal maxPrice) {
        this(query, category, minPrice, maxPrice, null, null);
    }

    /** A stay window is present — the search must restrict to available providers. */
    public boolean hasWindow() {
        return checkIn != null && checkOut != null;
    }
}
