package com.marketplace.catalog.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

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
@NamedInterface("catalog-spi")
public interface ProductTypeReadSpi {

    /**
     * The staff-surface verdict seam — the orders root's own
     * {@code requireAdmin()} precedent (the 2026-11-05 #806 round-3
     * review): the ADMIN decision must precede the boundary's
     * degenerate-page and unimplemented-argument early returns, so a
     * non-ADMIN caller always answers the authorization failure (never
     * a success-shaped empty page and never a validation error about
     * arguments the caller cannot use).
     */
    void requireAdmin();

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
     * The product-type projections for a batch of native UUIDs behind the
     * typed global IDs — the batch twin of {@link #findProductTypeById},
     * introduced for the {@code Product.productType} field's DGS data
     * loader (the 2026-11-03 #806 review's N+1 finding: a page of
     * products executed one owner lookup per product). The official DGS
     * data-loader pattern batches the page's references into ONE owner
     * call; this seam is that call.
     *
     * <p>The semantics are the single lookup's own, per id: every
     * requested id answers its {@link Optional} — an unknown but
     * well-formed UUID answers empty (the safe absence the nullable
     * field carries — never an error, never a fabricated type), a known
     * row answers its bounded projection. The returned map carries an
     * entry for EVERY requested id (a missing entry is a contract
     * violation — the loader's own every-key rule).</p>
     *
     * @param ids            the native UUIDs (decoded by the boundary's
     *                       codec; duplicates and an empty collection are
     *                       tolerated — an empty collection answers an
     *                       empty map)
     * @param authentication the caller, whose ADMIN authority is verified
     * @return every requested id mapped to its projection, empty for the
     *         unknown ones
     */
    Map<UUID, Optional<ProductTypeView>> findProductTypesByIds(Collection<UUID> ids,
                                                               Authentication authentication);

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
