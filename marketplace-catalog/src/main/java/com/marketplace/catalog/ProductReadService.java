package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductPage;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductSort;
import com.marketplace.catalog.spi.ProductView;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code product} and
 * {@code products} read roots, packet campaign-b4-c): the owning use
 * case for the store's product population's read surface. These are
 * Saleor PUBLIC storefront roots whose include-unpublished variant
 * carries the MANAGE_ORDERS/MANAGE_DISCOUNTS/MANAGE_PRODUCTS-class
 * permission — mapped through the platform's ADMIN capability (the
 * SaleorPermissionCatalog contract, enforced HERE at the owning
 * use-case boundary, never only in a resolver). The public population
 * is the entity's own ACTIVE display-lifecycle slice; the ADMIN
 * capability's scroll and lookup widen to every state — the
 * permission-aware population mapping the registered divergence row
 * records, honestly implementing Saleor's own public/include-unpublished
 * split over the platform's real lifecycle column.
 *
 * <p><b>The scroll is a REAL SQL keyset scroll</b> (the
 * {@code scrollStaffUsers} repository-keyset precedent): the products
 * table is populated commerce data, never bounded reference data — the
 * derived Spring Data JPA keyset methods ({@code Window}-returning,
 * {@code KeysetScrollPosition}/{@code Limit}-taking, the static
 * ordering baked into the method name with the {@code id} tiebreaker)
 * execute the continuation queries with the database's own keyset
 * predicate, and the per-element cursor positions the window carries
 * identify one row's own sort key — never an absolute offset.</p>
 */
@Service
public class ProductReadService implements ProductReadSpi {

    /** The platform's ADMIN authority name (the JWT converter's ROLE_ prefix contract). */
    static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final ProductRepository productRepository;

    public ProductReadService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * The product lookup by the native UUID behind the typed global ID —
     * the permission-aware readable population: an unknown but
     * well-formed UUID answers empty; a KNOWN but unpublished row
     * (SUSPENDED/ARCHIVED/DRAFT) answers empty for non-ADMIN callers
     * and is readable only by the ADMIN capability — never an error and
     * never a leak of the unpublished surface.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ProductView> findProductById(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return productRepository.findById(id)
                .filter(product -> isAdmin(authentication) || product.getStatus() == StoreProductStatus.ACTIVE)
                .map(ProductReadService::toView);
    }

    /**
     * The product population's REAL SQL forward-only keyset scroll — the
     * caller's own population: the ACTIVE slice for non-ADMIN callers
     * (the storefront population), every state for the ADMIN capability
     * (the include-unpublished variant). The page's total is the SAME
     * population's count — never another scope's.
     */
    @Override
    @Transactional(readOnly = true)
    public ProductPage scrollProducts(Authentication authentication,
                                      ProductSort sort,
                                      KeysetScrollPosition position,
                                      Limit limit) {
        if (sort == null || sort.field() == null) {
            throw new IllegalArgumentException("A product sort is required");
        }
        Window<Product> window;
        long total;
        if (isAdmin(authentication)) {
            // The include-unpublished population: every display state.
            window = sort.ascending()
                    ? productRepository.findByOrderByTitleAscIdAsc(position, limit)
                    : productRepository.findByOrderByTitleDescIdDesc(position, limit);
            total = productRepository.count();
        } else {
            // The public storefront population: the ACTIVE slice alone.
            window = sort.ascending()
                    ? productRepository.findByStatusOrderByTitleAscIdAsc(StoreProductStatus.ACTIVE, position, limit)
                    : productRepository.findByStatusOrderByTitleDescIdDesc(StoreProductStatus.ACTIVE, position, limit);
            total = productRepository.countByStatus(StoreProductStatus.ACTIVE);
        }
        return new ProductPage(window.map(ProductReadService::toView), total);
    }

    /** The ADMIN capability check — the owning use case's own authority fact. */
    private static boolean isAdmin(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ADMIN_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The bounded projection — only facts the {@code products} row owns:
     * the display title, the optional reusable goods schema reference,
     * and the display lifecycle state name.
     */
    private static ProductView toView(Product product) {
        return new ProductView(product.getId(), product.getTitle(),
                product.getProductTypeId(), product.getStatus().name());
    }
}
