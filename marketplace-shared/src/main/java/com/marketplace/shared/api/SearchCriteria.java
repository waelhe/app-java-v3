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
 *
 * <p>P1 (postgis integration plan §D-P6) adds the radius triple —
 * {@code latitude}/{@code longitude}/{@code radiusKm}, the same
 * type-gate philosophy as the stay window: the three components are
 * present TOGETHER or absent together (a partial presence is a 400 at
 * construction, before any query); the coordinate ranges mirror the V48
 * column constraints exactly ({@code [-90, 90]} / {@code [-180, 180]} —
 * the same bounds the {@code PropertyDetails} factory enforces on the
 * write side); the radius is bounded {@code (0, 50]} kilometers — the
 * plan's calibrated ceiling (debt D-I2, tuned with real usage data). The
 * radius granularity is the METER: the effective value is a whole number
 * of meters (finer precision is a 400 — the cache key's canonical
 * whole-meter segment and the ST_DWithin meters argument see the exact
 * same value, never a rounded one). The stored property coordinates are
 * {@code NUMERIC(9,6)} (V48), so the search center is normalized to the
 * same scale at construction: the query and the cache key always see the
 * identical value (the plan's D-P12 canonical segments — a center
 * precision beyond scale-6 is meaningless against scale-6 stored data,
 * and an un-normalized center would split the cache over equivalent
 * searches).
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
        Integer minAreaM2,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal radiusKm
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
        // P1 (postgis plan §D-P6): the radius triple — all three together
        // or none (the stay window's lesson: an incomplete group cannot
        // exist). The ranges mirror V48; the radius cap is the plan's
        // calibration ceiling; the meter granularity keeps the port's
        // long-meters argument exact.
        int radiusComponents = (latitude != null ? 1 : 0)
                + (longitude != null ? 1 : 0)
                + (radiusKm != null ? 1 : 0);
        if (radiusComponents != 0 && radiusComponents != 3) {
            throw new BadRequestException(
                    "latitude, longitude and radiusKm must be provided together");
        }
        if (radiusComponents == 3) {
            if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                    || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
                throw new BadRequestException("latitude must be within [-90, 90]");
            }
            if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                    || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
                throw new BadRequestException("longitude must be within [-180, 180]");
            }
            if (radiusKm.compareTo(BigDecimal.ZERO) <= 0
                    || radiusKm.compareTo(BigDecimal.valueOf(50)) > 0) {
                throw new BadRequestException("radiusKm must be within (0, 50]");
            }
            try {
                radiusKm.multiply(BigDecimal.valueOf(1000)).longValueExact();
            } catch (ArithmeticException wholeMetersOnly) {
                throw new BadRequestException(
                        "radiusKm precision is the meter (at most 3 decimal places)");
            }
            // The effective center at the stored coordinate scale — the
            // query and the cache key see the identical value (D-P12).
            latitude = latitude.setScale(6, java.math.RoundingMode.HALF_UP);
            longitude = longitude.setScale(6, java.math.RoundingMode.HALF_UP);
        }
    }

    /**
     * Legacy four-component form (pre-L27 callers): no stay window, no
     * guests criterion, no real-estate facets, no radius. Kept so every
     * existing construction site compiles unchanged.
     */
    public SearchCriteria(String query, String category, BigDecimal minPrice, BigDecimal maxPrice) {
        this(query, category, minPrice, maxPrice, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * The L27 six-component form (window, no guests): kept so every
     * window-era construction site compiles unchanged — {@code guests},
     * the real-estate facets and the radius triple stay {@code null}
     * (criterion-less).
     */
    public SearchCriteria(String query, String category, BigDecimal minPrice, BigDecimal maxPrice,
                          Instant checkIn, Instant checkOut) {
        this(query, category, minPrice, maxPrice, checkIn, checkOut, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * The L27/I6 seven-component form (the pre-L32 canonical): kept so the
     * pre-L32 construction sites (the controller) compile unchanged — the
     * real-estate facets and the radius triple stay {@code null}
     * (criterion-less).
     */
    public SearchCriteria(String query, String category, BigDecimal minPrice, BigDecimal maxPrice,
                          Instant checkIn, Instant checkOut, Integer guests) {
        this(query, category, minPrice, maxPrice, checkIn, checkOut, guests,
                null, null, null, null, null, null, null, null, null);
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

    /**
     * CodeRabbit PR #300 round 1: any CATALOG-side optional predicate is
     * present (category / price bounds / guests). The paged realestate
     * flows (the {@code area} and {@code distance} orderings) cannot apply
     * catalog predicates DB-side — their ordering is realestate-owned and
     * their predicates live on {@code property_details} — so the search
     * resolves the criteria-eligible ACTIVE set through the catalog port
     * first and the port pages through the intersection; without this gate
     * those flows silently ignored the catalog criteria (the review's
     * example: {@code guests=4&sort=distance} could return listings that
     * cannot accommodate four guests).
     */
    public boolean hasCatalogCriteria() {
        return category != null || minPrice != null || maxPrice != null || guests != null;
    }

    /**
     * P1 (postgis plan): the radius triple is present — the search takes
     * the radius branch (the dedicated dispatch, like the property flow).
     * The canonical constructor guarantees the triple is complete when
     * any component is present, so one null-check decides.
     */
    public boolean hasRadius() {
        return latitude != null && longitude != null && radiusKm != null;
    }

    /**
     * The radius in whole meters (the ST_DWithin argument — geography
     * distance unit). Valid only when {@link #hasRadius()} — the
     * constructor's meter-granularity gate makes the conversion exact.
     */
    public long radiusMeters() {
        return radiusKm.multiply(BigDecimal.valueOf(1000)).longValueExact();
    }
}
