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
 * window cannot exist — construction itself rejects an incomplete window
 * (one date without the other) or a
 * non-positive one ({@link BadRequestException} → HTTP 400 through
 * {@code GlobalExceptionHandler}), before any query runs (the acceptance
 * criterion "validate inputs before the predicate"). The valid window
 * itself IS half-open — {@code [checkIn, checkOut)} — and is never
 * rejected.
 *
 * <p>I6 (internal free plan §6, roadmap D1) adds the guest-capacity
 * criterion {@code guests} with the same type-gate philosophy ("same
 * lesson as the stay window"): a non-positive guests value cannot be
 * constructed — a 400 before any query, never a silently-empty page. A
 * {@code null} guests value is the criterion-less form (the legacy
 * behavior, byte-identical). The filter semantics on the query side:
 * {@code provider_listings.max_guests >= guests}; listings with NULL
 * capacity never match (undeclared capacity cannot satisfy a requirement).
 */
public record SearchCriteria(
        String query,
        String category,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Instant checkIn,
        Instant checkOut,
        Integer guests
) {

    /**
     * Canonical constructor — the window invariant and the guests gate:
     * both dates together, and {@code checkIn < checkOut} strictly
     * (an incomplete, zero-length or reversed window is a 400, not a
     * silently-empty page; the half-open {@code [checkIn, checkOut)}
     * interval itself is the valid form and is never rejected);
     * {@code guests}, when present, must be strictly positive (a guest
     * count of zero or less is meaningless — same rejection, before any
     * query runs).
     */
    public SearchCriteria {
        if (checkIn == null && checkOut == null) {
            // no window — the legacy form, unchanged
        } else if (checkIn == null || checkOut == null) {
            throw new BadRequestException("checkIn and checkOut must be provided together");
        } else if (!checkIn.isBefore(checkOut)) {
            throw new BadRequestException("checkIn must be strictly before checkOut");
        }
        if (guests != null && guests <= 0) {
            throw new BadRequestException("guests must be positive");
        }
    }

    /**
     * Legacy four-component form (pre-L27 callers): no stay window, no
     * guests criterion. Kept so every existing construction site compiles
     * unchanged.
     */
    public SearchCriteria(String query, String category, BigDecimal minPrice, BigDecimal maxPrice) {
        this(query, category, minPrice, maxPrice, null, null, null);
    }

    /**
     * The L27 six-component form (window, no guests): kept so every
     * window-era construction site compiles unchanged — {@code guests}
     * stays {@code null} (criterion-less).
     */
    public SearchCriteria(String query, String category, BigDecimal minPrice, BigDecimal maxPrice,
                          Instant checkIn, Instant checkOut) {
        this(query, category, minPrice, maxPrice, checkIn, checkOut, null);
    }

    /** A stay window is present — the search must restrict to available providers. */
    public boolean hasWindow() {
        return checkIn != null && checkOut != null;
    }
}
