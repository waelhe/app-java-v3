package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductTypePage;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductTypeSort;
import com.marketplace.catalog.spi.ProductTypeView;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code productType} and
 * {@code productTypes} read roots): the owning use case for the
 * product-type reference collection. Both reads are ADMIN-enforced at this
 * boundary (the platform's server-side mapping of Saleor's staff
 * MANAGE_PRODUCTS-class requirement — the RBAC divergence row), read-only,
 * and own every business decision the boundary needs.
 *
 * <p><b>The scroll executes in memory over the bounded reference
 * collection</b> — the honest scope decision, documented: the
 * {@code product_types} table is reusable reference data by the entity's
 * own contract, and the repository's own whole-table
 * {@code findAllByOrderByCodeAsc} seam is the codebase's standing
 * precedent for reading it. The scroll keeps the SAME semantics the
 * roster's SQL keyset executes: the cursor identifies one row's own sort
 * key {@code (name, id)}, the page follows it strictly in the requested
 * direction, and {@code hasNext} states whether elements remain — never an
 * absolute offset. If the collection ever grows beyond reference-data
 * scale, the SQL keyset methods (the {@code UserRepository} precedent)
 * replace the in-memory window without changing the seam.</p>
 */
@Service
public class ProductTypeReadService implements ProductTypeReadSpi {

    private final ProductTypeRepository productTypeRepository;

    public ProductTypeReadService(ProductTypeRepository productTypeRepository) {
        this.productTypeRepository = productTypeRepository;
    }

    /**
     * The product-type lookup by the native UUID behind the typed global
     * ID — ADMIN-enforced. An unknown but well-formed UUID answers empty:
     * the nullable root's own honest absence, never an error and never a
     * fabricated type.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Optional<ProductTypeView> findProductTypeById(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return productTypeRepository.findById(id).map(ProductTypeReadService::toView);
    }

    /**
     * The product-type collection's forward-only keyset scroll —
     * ADMIN-enforced, the owning NAME sort with the {@code id} tiebreaker,
     * plus the collection total Saleor's {@code totalCount} field carries.
     *
     * <p>The boundary's {@code KeysetCursorGate} has already validated the
     * cursor's {@code (name, id)} key set against this scroll's own
     * vocabulary; a position that is not the initial one must still carry
     * those keys here, or the honest {@link IllegalArgumentException}
     * answers — never a silent no-op.</p>
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public ProductTypePage scrollProductTypes(Authentication authentication,
                                              ProductTypeSort sort,
                                              KeysetScrollPosition position,
                                              Limit limit) {
        if (sort == null || sort.field() == null) {
            throw new IllegalArgumentException("A product-type sort is required");
        }
        List<ProductTypeView> types = new ArrayList<>();
        for (ProductType type : productTypeRepository.findAll()) {
            types.add(toView(type));
        }

        // The deterministic ordering — the owning sort field with the id
        // tiebreaker, both in the requested direction (the roster's SQL
        // keyset methods' own shape: ...AscIdAsc / ...DescIdDesc).
        Comparator<String> nameOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<UUID> idOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        types.sort(Comparator
                .comparing(ProductTypeView::name, nameOrder)
                .thenComparing(ProductTypeView::id, idOrder));

        // The keyset predicate: elements strictly AFTER the cursor's own
        // (name, id) sort key in the requested direction — the same
        // "greater than the sort key" the framework's keyset predicate
        // builds for the SQL scrolls.
        List<ProductTypeView> scrollable = types;
        if (position != null && !position.isInitial()) {
            if (!(position instanceof KeysetScrollPosition keyset)
                    || !keyset.scrollsForward()
                    || keyset.getKeys().get("name") == null
                    || keyset.getKeys().get("id") == null) {
                throw new IllegalArgumentException(
                        "The product-type scroll position must be a forward keyset over (name, id)");
            }
            String cursorName = String.valueOf(keyset.getKeys().get("name"));
            UUID cursorId = keyset.getKeys().get("id") instanceof UUID uuid
                    ? uuid
                    : UUID.fromString(String.valueOf(keyset.getKeys().get("id")));
            scrollable = types.stream()
                    .filter(type -> {
                        int byName = nameOrder.compare(type.name(), cursorName);
                        return byName != 0 ? byName > 0
                                : idOrder.compare(type.id(), cursorId) > 0;
                    })
                    .toList();
        }

        int pageSize = limit == null || !limit.isLimited()
                ? scrollable.size()
                : Math.min(limit.max(), scrollable.size());
        boolean hasNext = scrollable.size() > pageSize;
        List<ProductTypeView> content = scrollable.subList(0, pageSize);
        Window<ProductTypeView> window = Window.from(content, index ->
                ScrollPosition.forward(Map.of(
                        "name", content.get(index).name(),
                        "id", content.get(index).id())),
                hasNext);
        return new ProductTypePage(window, types.size());
    }

    /**
     * The bounded projection — only facts the {@code product_types} row
     * owns: the rendered primary display name ({@code nameEn} when present,
     * else {@code nameAr} — the bilingual row's own at-least-one guarantee
     * makes it non-null) and the stable public code as the Saleor slug.
     */
    private static ProductTypeView toView(ProductType type) {
        String name = type.getNameEn() != null ? type.getNameEn() : type.getNameAr();
        return new ProductTypeView(type.getId(), name, type.getCode());
    }
}
