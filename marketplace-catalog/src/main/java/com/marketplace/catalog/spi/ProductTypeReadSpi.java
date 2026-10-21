package com.marketplace.catalog.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code productType} and
 * {@code productTypes} read roots): the catalog owner's read seam for the
 * product-type reference collection. Authorization lives in the owning use
 * case ({@code ProductTypeReadService} enforces the platform's ADMIN
 * capability — the server-side mapping of Saleor's staff
 * MANAGE_PRODUCTS-class requirement at the use-case boundary, the RBAC
 * divergence row), never in a UI route guard and never duplicated at the
 * GraphQL boundary.
 */
public interface ProductTypeReadSpi {

    /**
     * The product-type projection by the native UUID behind the typed
     * global ID. An unknown but well-formed UUID answers empty (the safe
     * absence the nullable root carries — never an error, never a
     * fabricated type).
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the caller, whose ADMIN authority is verified
     * @return the product-type projection, empty when no row matches
     */
    Optional<ProductTypeView> findProductTypeById(UUID id, Authentication authentication);

    /**
     * One bounded page of the product-type collection as a forward-only
     * keyset scroll in the requested sort (the owning field + the
     * {@code id} tiebreaker — the {@code scrollStaffUsers} keyset
     * precedent). The returning page carries the collection total Saleor's
     * {@code totalCount} field expects.
     *
     * @param authentication the caller, whose ADMIN authority is verified
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap;
     *                       an unlimited limit answers the whole bounded
     *                       reference collection)
     * @return the page of product-type projections plus the collection
     *         total
     */
    ProductTypePage scrollProductTypes(Authentication authentication,
                                       ProductTypeSort sort,
                                       KeysetScrollPosition position,
                                       Limit limit);
}
