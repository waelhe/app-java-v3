package com.marketplace.shared.api;

import java.util.List;

/**
 * The framework-neutral pagination request the shared ports speak — the
 * request-side twin of {@link PagedResponse}.
 *
 * <p><b>Why this record exists:</b> the two paginated shared ports
 * ({@code CatalogSearchPort}, {@code RealestatePropertyFilterPort}) took
 * Spring Data's {@code Pageable} and returned its {@code Page} — the two
 * shared contracts that leaked framework types into the module boundary
 * (audit 2026-09-25 finding: "framework-neutral ports are unenforced";
 * re-measured 2026-09-28: 2 of the 24 ports import
 * {@code org.springframework.data.domain}). Under Spring Modulith's
 * boundary model the shared contract module is the last place framework
 * types should leak: a port that speaks {@code Pageable} drags every
 * consumer module into Spring Data's type system just to call a sibling
 * module. The port signatures now speak {@link PagedRequest} and
 * {@link PagedResponse}; the Spring Data types stay INSIDE the adapters
 * (the runtime keeps Spring Data pagination intact —
 * {@link SpringPagination} is the documented interop corner).
 *
 * <p><b>Why "PagedRequest" and not "PageRequest":</b> the plan's sketch
 * named the record {@code PageRequest(int page, int size)} — measured
 * against the call sites, that name collides with
 * {@code org.springframework.data.domain.PageRequest} in exactly the
 * files that must see both types (the catalog and realestate adapters
 * construct Spring {@code PageRequest}s internally while implementing the
 * neutral port). Mirroring the existing {@link PagedResponse} keeps the
 * pair symmetric and the adapters free of qualified-name churn.
 *
 * <param name="page">the zero-based page index (the {@code Pageable}
 *                   contract the whole codebase already speaks)</param>
 * <param name="size">the page size (entries per page)</param>
 * <param name="sort">the requested ordering as (property, direction)
 *                   pairs in priority order — carries exactly what the
 *                   ports measured as sort-bearing: the search surface's
 *                   mapped {@code priceCents}/{@code createdAt} orders
 *                   with the {@code id ASC} tiebreak, and the
 *                   realestate-owned {@code area}/{@code distance}
 *                   markers; empty means unsorted</param>
 */
public record PagedRequest(int page, int size, List<Order> sort) {

    /** One ordering step: a property name and its direction. */
    public record Order(String property, boolean descending) {
    }

    /**
     * The unsorted request — the plain browse form (every measured
     * non-search call site: provider pages, existence probes, category
     * browses).
     */
    public static PagedRequest of(int page, int size) {
        return new PagedRequest(page, size, List.of());
    }

    /** The sorted request with one or more ordering steps. */
    public static PagedRequest of(int page, int size, Order... sort) {
        return new PagedRequest(page, size, List.of(sort));
    }

    public boolean isSorted() {
        return !sort.isEmpty();
    }
}
