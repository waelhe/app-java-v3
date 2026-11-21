package com.marketplace.catalog.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code product} and
 * {@code products} read roots, packet campaign-b4-c): the catalog
 * owner's read seam for the store's product population (the SAME
 * authoritative {@code products} root the legacy platform roots read —
 * never a duplicate store). Authorization lives in the owning use case
 * ({@code ProductReadService}): these are Saleor PUBLIC storefront
 * roots whose include-unpublished variant requires the
 * MANAGE_ORDERS/MANAGE_DISCOUNTS/MANAGE_PRODUCTS-class permission —
 * mapped through the platform's ADMIN capability (the
 * SaleorPermissionCatalog contract). The public population is the
 * ACTIVE display lifecycle slice; an ADMIN caller's scroll includes
 * the unpublished states (SUSPENDED/ARCHIVED/DRAFT) and an ADMIN
 * caller's lookup may read them — a non-ADMIN's lookup of an
 * unpublished row answers the safe empty absence, never an error and
 * never a leak (the registered permission-aware population mapping
 * divergence).
 */
@NamedInterface("catalog-spi")
public interface ProductReadSpi {

    /**
     * The product lookup by the native UUID behind the typed global ID.
     * An unknown but well-formed UUID answers empty (the safe absence
     * the nullable root carries); a KNOWN but unpublished row answers
     * empty for non-ADMIN callers (the data-scope boundary) and is
     * readable only by the ADMIN capability — never an error and never
     * a leak.
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the caller, whose ADMIN authority widens the
     *                       readable population to the unpublished states
     * @return the product projection, empty when no readable row matches
     */
    Optional<ProductView> findProductById(UUID id, Authentication authentication);

    /**
     * One bounded page of the product population as a REAL SQL
     * forward-only keyset scroll in the requested sort (the owning field
     * + the {@code id} tiebreaker — the {@code scrollStaffUsers}
     * repository-keyset precedent, the products table is NOT bounded
     * reference data). The scrolled population is the caller's own: the
     * ACTIVE slice for non-ADMIN callers, every state for the ADMIN
     * capability — and the returning page's total is the SAME
     * population's count, never another scope's.
     *
     * @param authentication the caller, whose ADMIN authority selects the
     *                       include-unpublished population
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap)
     * @return the page of product projections plus the scrolled
     *         population's total
     */
    ProductPage scrollProducts(Authentication authentication,
                               ProductSort sort,
                               KeysetScrollPosition position,
                               Limit limit);
}
