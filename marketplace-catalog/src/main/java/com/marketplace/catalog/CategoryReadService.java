package com.marketplace.catalog;

import com.marketplace.catalog.spi.CategoryPage;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategorySort;
import com.marketplace.catalog.spi.CategoryView;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code category} and
 * {@code categories} read roots, packet campaign-b4-a): the owning use
 * case for the listing-category registry's read surface. These are
 * Saleor PUBLIC storefront roots — the pinned contract requires no
 * permission for published categories, and the platform registry (the
 * controlled vocabulary the listing {@code category} column references,
 * the V70-seeded open vocabulary) carries no publication flag: every
 * ACTIVE row is published reference data. The service therefore enforces
 * NO role gate — the platform's authentication boundary for the public
 * GraphQL surface is the {@code /graphql} chain itself (the registered
 * public-root mapping divergence; the ADMIN-gated identity/attribute
 * slices are the staff-root contrast). The include-unpublished variant
 * of the Saleor root (MANAGE_ORDERS/MANAGE_DISCOUNTS/
 * MANAGE_PRODUCTS-class) has no platform surface to map — there is no
 * unpublished category to include — so it is recorded as not applicable,
 * never silently approximated.
 *
 * <p><b>The scroll executes in memory over the bounded reference
 * registry</b> — the honest scope decision, the {@code AttributeReadService}
 * precedent's own documented choice for reference collections: the
 * {@code categories} table is a controlled vocabulary that evolves by
 * INSERT (the business model's own open-vocabulary gate), and the
 * repository's whole-table {@code findAllByOrderByPositionAsc} seam is
 * the registry's standing public read. The scroll keeps the SAME
 * semantics the roster's SQL keyset executes: the cursor identifies one
 * row's own sort key (the executed sort field, {@code id}), the page
 * follows it strictly in the requested direction, and {@code hasNext}
 * states whether elements remain — never an absolute offset. If the
 * registry ever grows beyond reference-data scale, SQL keyset methods
 * (the {@code UserRepository} precedent) replace the in-memory window
 * without changing the seam.</p>
 *
 * <p><b>The NAME sort key is total</b>: a registry row may legally carry
 * neither bilingual name (both columns are nullable — the registered
 * nullability divergence). Such a row's ORDERING key is the empty string
 * (deterministic and non-null, so the keyset positions — and therefore
 * the opaque cursors — never carry a null value); its WIRE name stays
 * the honest null of the bounded projection. Ascending orders the
 * empty-key rows first, descending last — self-consistent under
 * reversal, deterministic with the {@code id} tiebreaker.</p>
 */
@Service
public class CategoryReadService implements CategoryReadSpi {

    private final CategoryRepository categoryRepository;

    public CategoryReadService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * The category lookup by the native UUID behind the typed global ID.
     * An unknown but well-formed UUID answers empty: the nullable root's
     * own honest absence, never an error and never a fabricated category.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CategoryView> findCategoryById(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return categoryRepository.findById(id).map(CategoryReadService::toView);
    }

    /**
     * The category lookup by the public slug (the row's immutable
     * {@code code} — the Saleor slug mapping). An unknown but
     * well-formed slug answers empty: the nullable root's own honest
     * absence, never an error and never a fabricated category.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CategoryView> findCategoryBySlug(String slug, Authentication authentication) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return categoryRepository.findByCode(slug).map(CategoryReadService::toView);
    }

    /**
     * The category registry's forward-only keyset scroll — the owning
     * sort field (the rendered NAME, with the {@code id} tiebreaker),
     * plus the registry total Saleor's {@code totalCount} field carries.
     *
     * <p>The boundary's {@code KeysetCursorGate} has already validated the
     * cursor's {@code (sortProperty, id)} key set against this scroll's own
     * vocabulary; a position that is not the initial one must still carry
     * those keys here, or the honest {@link IllegalArgumentException}
     * answers — never a silent no-op.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public CategoryPage scrollCategories(Authentication authentication,
                                         CategorySort sort,
                                         KeysetScrollPosition position,
                                         Limit limit) {
        if (sort == null || sort.field() == null) {
            throw new IllegalArgumentException("A category sort is required");
        }
        List<CategoryView> categories = new ArrayList<>();
        for (Category category : categoryRepository.findAll()) {
            categories.add(toView(category));
        }

        // The deterministic ordering — the owning sort field's TOTAL
        // ordering key with the id tiebreaker, both in the requested
        // direction (the roster's SQL keyset methods' own shape:
        // ...AscIdAsc / ...DescIdDesc).
        Comparator<String> fieldOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<UUID> idOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Function<CategoryView, String> fieldExtractor = CategoryReadService::orderingName;
        categories.sort(Comparator
                .comparing(fieldExtractor, fieldOrder)
                .thenComparing(CategoryView::id, idOrder));

        // The keyset predicate: elements strictly AFTER the cursor's own
        // (sortProperty, id) sort key in the requested direction — the
        // same "greater than the sort key" the framework's keyset
        // predicate builds for the SQL scrolls.
        String sortProperty = sort.property();
        List<CategoryView> scrollable = categories;
        if (position != null && !position.isInitial()) {
            if (!(position instanceof KeysetScrollPosition keyset)
                    || !keyset.scrollsForward()
                    || keyset.getKeys().get(sortProperty) == null
                    || keyset.getKeys().get("id") == null) {
                throw new IllegalArgumentException(
                        "The category scroll position must be a forward keyset over (" + sortProperty + ", id)");
            }
            String cursorField = String.valueOf(keyset.getKeys().get(sortProperty));
            UUID cursorId = keyset.getKeys().get("id") instanceof UUID uuid
                    ? uuid
                    : UUID.fromString(String.valueOf(keyset.getKeys().get("id")));
            scrollable = categories.stream()
                    .filter(category -> {
                        int byField = fieldOrder.compare(fieldExtractor.apply(category), cursorField);
                        return byField != 0 ? byField > 0
                                : idOrder.compare(category.id(), cursorId) > 0;
                    })
                    .toList();
        }

        int pageSize = limit == null || !limit.isLimited()
                ? scrollable.size()
                : Math.min(limit.max(), scrollable.size());
        boolean hasNext = scrollable.size() > pageSize;
        List<CategoryView> content = scrollable.subList(0, pageSize);
        Window<CategoryView> window = Window.from(content, index ->
                ScrollPosition.forward(Map.of(
                        sortProperty, fieldExtractor.apply(content.get(index)),
                        "id", content.get(index).id())),
                hasNext);
        return new CategoryPage(window, categories.size());
    }

    /**
     * The row's TOTAL ordering key for the NAME sort — the rendered
     * primary display name, with the empty string for a row carrying
     * neither bilingual name (deterministic and non-null for the keyset
     * positions; the wire name stays the honest null — see the class
     * documentation).
     */
    private static String orderingName(CategoryView view) {
        return view.name() != null ? view.name() : "";
    }

    /**
     * The bounded projection — only facts the {@code categories} row
     * owns: the rendered primary display name ({@code nameEn} when
     * present, else {@code nameAr} — null only when the row carries
     * neither, the registered nullability divergence), the bilingual
     * second name, the stable public code as the Saleor slug, and the
     * registry's own display order.
     */
    private static CategoryView toView(Category category) {
        String name = category.getNameEn() != null ? category.getNameEn() : category.getNameAr();
        return new CategoryView(category.getId(), name, category.getNameAr(),
                category.getCode(), category.getPosition());
    }
}
