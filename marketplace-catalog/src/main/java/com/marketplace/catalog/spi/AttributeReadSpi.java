package com.marketplace.catalog.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code attribute} and
 * {@code attributes} read roots, the declared continuation of the
 * campaign-b2-a2 product-type slice): the catalog owner's read seam for
 * the typed attribute reference collection. Authorization lives in the
 * owning use case ({@code AttributeReadService} enforces the platform's
 * ADMIN capability — the server-side mapping of Saleor's staff
 * MANAGE_PRODUCTS-class requirement at the use-case boundary, the RBAC
 * divergence row), never in a UI route guard and never duplicated at the
 * GraphQL boundary.
 */
@NamedInterface("catalog-spi")
public interface AttributeReadSpi {

    /**
     * The attribute lookup by the native UUID behind the typed global ID.
     * An unknown but well-formed UUID answers empty (the safe absence the
     * nullable root carries — never an error, never a fabricated
     * attribute).
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the caller, whose ADMIN authority is verified
     * @return the attribute projection, empty when no row matches
     */
    Optional<AttributeView> findAttributeById(UUID id, Authentication authentication);

    /**
     * The attribute lookup by the public slug (the row's immutable
     * {@code code} — the Saleor slug mapping). An unknown but well-formed
     * slug answers empty (the safe absence the nullable root carries —
     * never an error, never a fabricated attribute).
     *
     * @param slug           the public code (never blank at this seam —
     *                       the boundary validates the argument shape)
     * @param authentication the caller, whose ADMIN authority is verified
     * @return the attribute projection, empty when no row matches
     */
    Optional<AttributeView> findAttributeBySlug(String slug, Authentication authentication);

    /**
     * One bounded page of the attribute collection as a forward-only
     * keyset scroll in the requested sort (the owning field + the
     * {@code id} tiebreaker — the {@code scrollStaffUsers}/
     * {@code scrollProductTypes} keyset precedent). The returning page
     * carries the collection total Saleor's {@code totalCount} field
     * expects.
     *
     * @param authentication the caller, whose ADMIN authority is verified
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap;
     *                       an unlimited limit answers the whole bounded
     *                       reference collection)
     * @return the page of attribute projections plus the collection total
     */
    AttributePage scrollAttributes(Authentication authentication,
                                   AttributeSort sort,
                                   KeysetScrollPosition position,
                                   Limit limit);
}
