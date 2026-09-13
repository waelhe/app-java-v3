package com.marketplace.shared.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
 *
 * <p>L32 (realestate systems plan §5) adds the real-estate facet criteria —
 * the plan's D-E5 decision, same type-gate philosophy, gates bounded by
 * the FIELD TYPE (the CodeRabbit round-1 note that shaped the plan):
 * <ul>
 *   <li>{@code locationId} — a UUID-typed criterion: absent, or resolved
 *       against the geo tree by the search module (unknown id = 404 from
 *       the geo port, never a silently-empty page);</li>
 *   <li>{@code purpose}/{@code propertyType} — enum-typed criteria: an
 *       invalid name cannot be constructed (binding fails at the surface
 *       with a 400), and the filter delegates to the realestate module
 *       through {@code RealestatePropertyFilterPort} (the plan's
 *       set-restriction integration — no cross-module query);</li>
 *   <li>{@code minRooms}/{@code minBathrooms}/{@code minAreaM2} — positive
 *       or absent: zero/negative is a 400 at construction, before any
 *       query (the plan's acceptance criterion).</li>
 * </ul>
 * A criteria object with none of the six present is the legacy form —
 * every pre-L32 call site compiles and behaves byte-identically.
 */
public record SearchCriteria(
        String query,
        String category,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Instant checkIn,
        Instant checkOut,
        Integer guests,
        UUID locationId,
        PropertyPurpose purpose,
        PropertyType propertyType,
        Integer minRooms,
        Integer minBathrooms,
        Integer minAreaM2
) {

    /**
     * Canonical constructor — every type gate of the record: the window
     * invariant (both dates together, {@code checkIn < checkOut} strictly),
     * the positive-or-absent numeric criteria ({@code guests},
     * {@code minRooms}, {@code minBathrooms}, {@code minAreaM2} — a
     * meaningless zero/negative is a 400, never a silently-empty page).
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
        if (minRooms != null && minRooms <= 0) {
            throw new BadRequestException("minRooms must be positive");
        }
        if (minBathrooms != null && minBathrooms <= 0) {
            throw new BadRequestException("minBathrooms must be positive");
        }
        if (minAreaM2 != null && minAreaM2 <= 0) {
            throw new BadRequestException("minAreaM2 must be positive");
        }
    }

    /**
     * Legacy four-component form (pre-L27 callers): no stay window, no
     * guests criterion, no real-estate facets. Kept so every existing
     * construction site compiles unchanged.
     */
    public SearchCriteria(String query, String category, BigDecimal minPrice, BigDecimal maxPrice) {
        this(query, category, minPrice, maxPrice, null, null, null,
                null, null, null, null, null, null);
    }

    /**
     * The L27 six-component form (window, no guests): kept so every
     * window-era construction site compiles unchanged — {@code guests}
     * stays {@code null} (criterion-less).
     */
    public SearchCriteria(String query, String category, BigDecimal minPrice, BigDecimal maxPrice,
                          Instant checkIn, Instant checkOut) {
        this(query, category, minPrice, maxPrice, checkIn, checkOut, null,
                null, null, null, null, null, null);
    }

    /**
     * The L27/I6 seven-component form (the pre-L32 canonical): kept so the
     * pre-L32 construction sites (the controller) compile unchanged — the
     * real-estate facets stay {@code null} (criterion-less).
     */
    public SearchCriteria(String query, String category, BigDecimal minPrice, BigDecimal maxPrice,
                          Instant checkIn, Instant checkOut, Integer guests) {
        this(query, category, minPrice, maxPrice, checkIn, checkOut, guests,
                null, null, null, null, null, null);
    }

    /** A stay window is present — the search must restrict to available providers. */
    public boolean hasWindow() {
        return checkIn != null && checkOut != null;
    }

    /**
     * L32: any real-estate facet is present — the search must resolve the
     * property restriction through the realestate filter port before any
     * catalog query.
     */
    public boolean hasPropertyCriteria() {
        return locationId != null || purpose != null || propertyType != null
                || minRooms != null || minBathrooms != null || minAreaM2 != null;
    }
}
