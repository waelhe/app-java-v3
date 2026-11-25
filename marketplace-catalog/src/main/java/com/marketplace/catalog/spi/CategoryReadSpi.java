package com.marketplace.catalog.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code category} and
 * {@code categories} read roots, packet campaign-b4-a): the catalog
 * owner's read seam for the listing-category registry (the controlled
 * vocabulary the listing {@code category} column references — the SAME
 * registry the public REST vocabulary read serves, never a duplicate
 * store). Authorization lives in the owning use case
 * ({@code CategoryReadService}): these roots are Saleor PUBLIC
 * storefront roots — the pinned contract requires no permission for
 * published content, and the platform registry carries no publication
 * flag (every row is published reference data), so the service enforces
 * no role gate and the platform's authentication boundary is the
 * {@code /graphql} chain itself (the registered public-root mapping
 * divergence) — never a UI route guard and never duplicated at the
 * GraphQL boundary.
 */
@NamedInterface("catalog-spi")
public interface CategoryReadSpi {

    /**
     * The category lookup by the native UUID behind the typed global ID.
     * An unknown but well-formed UUID answers empty (the safe absence the
     * nullable root carries — never an error, never a fabricated
     * category).
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the authenticated caller (the public-root
     *                       mapping — no role gate; the chain guarantees
     *                       authentication)
     * @return the category projection, empty when no row matches
     */
    Optional<CategoryView> findCategoryById(UUID id, Authentication authentication);

    /**
     * The category lookup by the public slug (the row's immutable
     * {@code code} — the Saleor slug mapping). An unknown but
     * well-formed slug answers empty (the safe absence the nullable root
     * carries — never an error, never a fabricated category).
     *
     * @param slug           the public code (never blank at this seam —
     *                       the boundary validates the argument shape)
     * @param authentication the authenticated caller (the public-root
     *                       mapping — no role gate)
     * @return the category projection, empty when no row matches
     */
    Optional<CategoryView> findCategoryBySlug(String slug, Authentication authentication);

    /**
     * One bounded page of the category registry as a forward-only keyset
     * scroll in the requested sort (the owning field + the {@code id}
     * tiebreaker — the {@code scrollStaffUsers}/
     * {@code scrollProductTypes}/ {@code scrollAttributes} keyset
     * precedent). The returning page carries the registry total Saleor's
     * {@code totalCount} field expects.
     *
     * @param authentication the authenticated caller (the public-root
     *                       mapping — no role gate)
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap;
     *                       an unlimited limit answers the whole bounded
     *                       reference registry)
     * @return the page of category projections plus the registry total
     */
    CategoryPage scrollCategories(Authentication authentication,
                                  CategorySort sort,
                                  KeysetScrollPosition position,
                                  Limit limit);
}
