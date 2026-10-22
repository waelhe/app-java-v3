package com.marketplace.catalog.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code productVariant}
 * and {@code productVariants} read roots, packet campaign-b5-a): the
 * catalog owner's read seam for the store's product-variant population
 * (the SAME authoritative {@code product_variants} rows the cart's
 * reservation arithmetic writes — never a duplicate store).
 * Authorization lives in the owning use case
 * ({@code ProductVariantReadService}): these are Saleor PUBLIC
 * storefront roots whose include-unpublished variant requires the
 * MANAGE_ORDERS/MANAGE_DISCOUNTS/MANAGE_PRODUCTS-class permission —
 * mapped through the platform's ADMIN capability (the
 * SaleorPermissionCatalog contract). The public population is the
 * TWO-ROW gate the platform's own reservation write carries verbatim
 * (the variant's ACTIVE shelf AND the parent product's ACTIVE shelf —
 * the {@code ProductVariantRepository.reserve} predicate's own read
 * mirror); an ADMIN caller's scroll and lookup widen to every state
 * and every parent — the registered permission-aware population
 * mapping divergence.
 */
@NamedInterface("catalog-spi")
public interface ProductVariantReadSpi {

    /**
     * The variant lookup by the native UUID behind the typed global ID.
     * An unknown but well-formed UUID answers empty (the safe absence
     * the nullable root carries); a KNOWN row outside the caller's
     * readable population — a non-ACTIVE variant, or an ACTIVE variant
     * under a non-ACTIVE parent product — answers empty for non-ADMIN
     * callers (the two-row data-scope boundary) and is readable only
     * by the ADMIN capability — never an error and never a leak.
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the caller, whose ADMIN authority widens the
     *                       readable population to every state and parent
     * @return the variant projection (parent projection included),
     *         empty when no readable row matches
     */
    Optional<ProductVariantView> findVariantById(UUID id, Authentication authentication);

    /**
     * Resolve a variant already referenced by an owned aggregate (for example
     * a cart line) without re-applying the public shelf filter. The caller
     * owns the authorization decision; lifecycle states stay observable so a
     * suspended product does not vanish from the owner's own record.
     */
    Optional<ProductVariantView> findVariantSnapshot(UUID id);

    /**
     * One bounded page of the variant population as a REAL SQL
     * forward-only keyset scroll in the requested sort (the owning
     * field + the {@code id} tiebreaker — the
     * {@code scrollStaffUsers} repository-keyset precedent; the
     * product_variants table is populated commerce data, never
     * bounded reference data). The scrolled population is the
     * caller's own: the two-row-gated public population (variant
     * ACTIVE AND parent product ACTIVE) for non-ADMIN callers, every
     * row for the ADMIN capability — and the returning page's total
     * is the SAME population's count, never another scope's.
     *
     * @param authentication the caller, whose ADMIN authority selects the
     *                       include-unpublished population
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap)
     * @return the page of variant projections (parent projections
     *         included) plus the scrolled population's total
     */
    ProductVariantPage scrollVariants(Authentication authentication,
                                      ProductVariantSort sort,
                                      KeysetScrollPosition position,
                                      Limit limit);
}
