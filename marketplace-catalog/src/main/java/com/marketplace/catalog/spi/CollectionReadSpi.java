package com.marketplace.catalog.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor {@code collection}
 * and {@code collections} read roots, packet agent-2): the catalog owner's
 * read seam for the storefront collection registry. These are Saleor
 * PUBLIC storefront roots — the pinned contract requires no permission
 * for published collections, and the registry's own publication fact
 * ({@code is_published}) carries the include-unpublished law: the
 * ADMIN-class caller (the platform's mapping of the pinned
 * MANAGE_ORDERS/MANAGE_DISCOUNTS/MANAGE_PRODUCTS permission family) sees
 * every live row; any other authenticated caller sees the published rows
 * alone — enforced HERE at the owning use-case boundary (the service
 * receives the {@code Authentication}), never only in a resolver.
 *
 * <p>The scroll contract is the platform's keyset law (the frozen
 * pagination contract): forward-only positions, cursors that identify a
 * row's own sort key (the executed sort field, with the {@code id}
 * tiebreaker), {@code hasNext} from the window's own facts, and the
 * honest {@code total} — never an absolute offset. The membership scroll
 * ({@code Collection.products}) rides the membership registry's own
 * (position, id) ordering key with the same law, and the member-product
 * projections come from the products table the same module owns.</p>
 */
@NamedInterface("catalog-spi")
public interface CollectionReadSpi {

    /**
     * The collection lookup by the native UUID behind the typed global ID.
     * An unknown but well-formed UUID answers empty: the nullable root's
     * own honest absence, never an error and never a fabricated
     * collection. The publication law governs: a non-ADMIN caller's
     * lookup of an unpublished row answers the same safe absence.
     *
     * @param id              the row's native UUID (null answers empty)
     * @param authentication  the authenticated caller (the publication
     *                        law's own gate — never null)
     * @return the visible row's projection, or empty
     */
    Optional<CollectionView> findCollectionById(UUID id, Authentication authentication);

    /**
     * The collection lookup by the public slug. An unknown but
     * well-formed slug answers empty: the nullable root's own honest
     * absence. The publication law governs identically.
     *
     * @param slug            the public slug (null/blank answers empty)
     * @param authentication  the authenticated caller (never null)
     * @return the visible row's projection, or empty
     */
    Optional<CollectionView> findCollectionBySlug(String slug, Authentication authentication);

    /**
     * The collection registry's forward-only keyset scroll — the owning
     * sort field (the rendered NAME, with the {@code id} tiebreaker),
     * plus the visible registry total the Saleor {@code totalCount}
     * field carries.
     *
     * @param authentication  the authenticated caller (never null)
     * @param sort            the executed sort (never null)
     * @param position        the keyset scroll position (the initial
     *                        position for a fresh page)
     * @param limit           the page-size limit
     * @return one bounded page of the visible registry
     */
    CollectionPage scrollCollections(Authentication authentication,
                                     CollectionSort sort,
                                     KeysetScrollPosition position,
                                     Limit limit);

    /**
     * One collection's member-products scroll — the membership registry's
     * own (position, id) ordering key, forward-only, over the member
     * products the same module's products table owns. The member-product
     * publication law governs: a non-ADMIN caller's scroll carries the
     * published (ACTIVE) products alone, and the total answers the same
     * visibility.
     *
     * @param collectionId    the collection's native UUID (never null)
     * @param position        the keyset scroll position over (position, id)
     * @param limit           the page-size limit
     * @param authentication  the authenticated caller (never null)
     * @return one bounded page of the visible member products
     */
    CollectionProductsPage scrollCollectionProducts(UUID collectionId,
                                                    KeysetScrollPosition position,
                                                    Limit limit,
                                                    Authentication authentication);
}
