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
 *
 * <p>P1 (postgis integration plan §D-P11) adds the {@code distance} marker
 * — NEAREST-FIRST only: the native radius query's baked
 * {@code ORDER BY ST_Distance(...) ASC, listing_id ASC} is the whole
 * distance contract (the plan's guard: "sort=distance orders nearest
 * first"). A farthest-first request is a 400 at the whitelist — the loud
 * boundary: never a silent direction flip (an asc-baked query serving a
 * desc request) and never a second query pair for a use case the plan
 * does not carry.
 */
final class SearchSorts {

    /** The area sort marker (the realestate-owned field — see the class doc). */
    static final String AREA = "area";

    /** The distance sort marker (the realestate-owned radius ordering). */
    static final String DISTANCE = "distance";

    private SearchSorts() {
    }

    /**
     * Normalizes the requested sort: every property must be whitelisted
     * (400 otherwise); mapped properties replace their API names; the id
     * tiebreak is appended. An unsorted pageable passes through unchanged
     * (the legacy byte-identical path — no synthetic sort in the key).
     */
    static Pageable normalize(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }
        // CodeRabbit PR #299 round 1 (P1-extended): a FLOW MARKER (area or
        // distance) cannot COMBINE with another sort property — the routed
        // flow owns the whole ordering, so a mixed request would silently
        // apply only the marker's part. The boundary rejects it loudly.
        List<Sort.Order> orders = pageable.getSort().stream().toList();
        long markerOrders = orders.stream()
                .filter(order -> AREA.equals(order.getProperty())
                        || DISTANCE.equals(order.getProperty()))
                .count();
        if (markerOrders > 0 && orders.size() > markerOrders) {
            throw new BadRequestException(
                    "sort=area/distance cannot combine with other sort properties"
                            + " (the routed flow owns the whole ordering)");
        }
        List<Sort.Order> mapped = orders.stream()
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
            case DISTANCE -> distanceOrder(order);
            default -> throw new BadRequestException(
                    "unsupported sort property: " + order.getProperty()
                            + " (supported: price, newest, area, distance)");
        };
    }

    /**
     * P1: nearest-first only — the baked ORDER BY is the whole distance
     * contract; a farthest-first request is rejected loudly at the
     * boundary, never silently flipped.
     */
    private static Sort.Order distanceOrder(Sort.Order order) {
        if (order.getDirection() == Sort.Direction.DESC) {
            throw new BadRequestException(
                    "sort=distance orders nearest-first (descending is not supported)");
        }
        return order; // marker — the service routes it to the radius flow
    }

    /** Whether the (normalized) sort requests the area ordering. */
    static boolean isAreaSorted(Pageable pageable) {
        return pageable.getSort().stream()
                .anyMatch(order -> AREA.equals(order.getProperty()));
    }

    /**
     * Whether the (normalized) sort requests the distance ordering — the
     * radius flow's marker (it requires the radius criteria; the service
     * validates the pairing at the dispatch entry, before any query).
     */
    static boolean isDistanceSorted(Pageable pageable) {
        return pageable.getSort().stream()
                .anyMatch(order -> DISTANCE.equals(order.getProperty()));
    }
}
