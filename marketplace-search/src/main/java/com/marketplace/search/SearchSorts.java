package com.marketplace.search;

import com.marketplace.shared.api.BadRequestException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * L32 (realestate systems plan §5): the faceted search's sort whitelist —
 * the plan's "supported sorts" (price asc/desc, newest, area), validated at
 * the boundary with the type-gate philosophy: an unsupported sort property
 * is a 400 before any query (previously the native queries' baked ORDER BY
 * made ANY sort parameter a SQL error — 500; the whitelist turns the
 * surface honest).
 *
 * <p>Mapped names: {@code price} → the entity's {@code priceCents};
 * {@code newest} → {@code createdAt}; {@code area} stays a MARKER property
 * (the realestate module owns the area column — the service routes
 * area-sorted requests to the paged property flow). The id ASC tiebreak is
 * appended for every mapped sort (deterministic offset pagination — "no
 * wobbling pages").
 */
final class SearchSorts {

    /** The area sort marker (the realestate-owned field — see the class doc). */
    static final String AREA = "area";

    private SearchSorts() {
    }

    /**
     * Normalizes the requested sort: every property must be whitelisted
     * (400 otherwise); mapped properties replace their API names; the id
     * tiebreak is appended. An unsorted pageable passes through unchanged
     * (the legacy byte-identical path — no synthetic sort in the key).
     *
     * <p>CodeRabbit PR #299 round 1: the {@code area} marker cannot COMBINE
     * with another sort property — the property flow routes on the marker
     * and owns the whole ordering, so a mixed request would silently apply
     * only the area part. The boundary rejects it loudly instead.
     */
    static Pageable normalize(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }
        long areaOrders = pageable.getSort().stream()
                .filter(order -> AREA.equals(order.getProperty()))
                .count();
        if (areaOrders > 0 && pageable.getSort().stream().count() > areaOrders) {
            throw new BadRequestException(
                    "sort=area cannot combine with other sort properties"
                            + " (the property flow owns the whole ordering)");
        }
        List<Sort.Order> mapped = pageable.getSort().stream()
                .map(SearchSorts::mapOrder)
                .toList();
        return org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(mapped).and(Sort.by(Sort.Direction.ASC, "id")));
    }

    private static Sort.Order mapOrder(Sort.Order order) {
        return switch (order.getProperty()) {
            case "price" -> Sort.Order.by("priceCents").with(order.getDirection());
            case "newest" -> Sort.Order.by("createdAt").with(order.getDirection());
            case AREA -> order; // marker — the service routes it to the property flow
            default -> throw new BadRequestException(
                    "unsupported sort property: " + order.getProperty()
                            + " (supported: price, newest, area)");
        };
    }

    /** Whether the (normalized) sort requests the area ordering. */
    static boolean isAreaSorted(Pageable pageable) {
        return pageable.getSort().stream()
                .anyMatch(order -> AREA.equals(order.getProperty()));
    }
}
