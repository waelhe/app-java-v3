package com.marketplace.catalog;

import com.marketplace.catalog.spi.CollectionPage;
import com.marketplace.catalog.spi.CollectionProductsPage;
import com.marketplace.catalog.spi.CollectionReadSpi;
import com.marketplace.catalog.spi.CollectionSort;
import com.marketplace.catalog.spi.CollectionView;
import com.marketplace.catalog.spi.ProductView;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor {@code collection}
 * and {@code collections} read roots, packet agent-2): the owning use
 * case for the storefront collection registry's read surface. These are
 * Saleor PUBLIC storefront roots — the pinned contract requires no
 * permission for published collections — and the registry carries its
 * own publication fact ({@code is_published}): the include-unpublished
 * variant of the pinned root (the MANAGE_ORDERS/MANAGE_DISCOUNTS/
 * MANAGE_PRODUCTS permission family) maps to the platform's ADMIN
 * capability, enforced HERE at the owning use-case boundary over the
 * received {@code Authentication} (the frozen authorization contract) —
 * an ADMIN-class caller sees every live row, any other authenticated
 * caller sees the published rows alone, and an unpublished row's lookup
 * by a non-ADMIN caller answers the same safe absence as a missing one
 * (never an existence disclosure).
 *
 * <p><b>The scroll executes in memory over the bounded reference
 * registry</b> — the {@code CategoryReadService} precedent's own
 * documented choice for reference collections: the {@code collections}
 * table is curated storefront data that evolves by ADMIN writes, and the
 * repository's whole-registry seam is the registry's standing public
 * read. The scroll keeps the SAME semantics the roster's SQL keyset
 * executes: the cursor identifies one row's own sort key (the executed
 * sort field, {@code id}), the page follows it strictly in the requested
 * direction, and {@code hasNext} states whether elements remain — never
 * an absolute offset. If the registry ever grows beyond reference-data
 * scale, SQL keyset methods (the {@code UserRepository} precedent)
 * replace the in-memory window without changing the seam.</p>
 *
 * <p><b>The member-products scroll</b> ({@code Collection.products})
 * rides the membership registry's own (position, id) ordering key over
 * the same in-memory law, and the member-product projections come from
 * the {@code products} table the same module owns. The member-product
 * publication law governs identically: a non-ADMIN caller's scroll
 * carries the published (ACTIVE) products alone — the frozen
 * "unpublished product never readable without delegation" contract —
 * and the total answers the same visibility.</p>
 *
 * <p><b>The NAME sort key is total</b>: a registry row may legally
 * carry neither bilingual name (both columns are nullable). Such a row's
 * ORDERING key is the empty string (deterministic and non-null, so the
 * keyset positions — and therefore the opaque cursors — never carry a
 * null value); its WIRE name stays the honest null of the bounded
 * projection. Ascending orders the empty-key rows first, descending
 * last — self-consistent under reversal, deterministic with the
 * {@code id} tiebreaker.</p>
 */
@Service
public class CollectionReadService implements CollectionReadSpi {

    private final CollectionRepository collectionRepository;
    private final CollectionProductRepository collectionProductRepository;
    private final ProductRepository productRepository;

    public CollectionReadService(CollectionRepository collectionRepository,
                                 CollectionProductRepository collectionProductRepository,
                                 ProductRepository productRepository) {
        this.collectionRepository = collectionRepository;
        this.collectionProductRepository = collectionProductRepository;
        this.productRepository = productRepository;
    }

    /**
     * The collection lookup by the native UUID behind the typed global ID.
     * The publication law governs: an unknown but well-formed UUID (or an
     * unpublished row for a non-ADMIN caller) answers the same safe empty
     * — never an error and never a fabricated collection.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CollectionView> findCollectionById(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return collectionRepository.findById(id)
                .filter(row -> visibleTo(row, authentication))
                .map(CollectionReadService::toView);
    }

    /**
     * The collection lookup by the public slug — the same publication law,
     * the same safe-empty contract.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CollectionView> findCollectionBySlug(String slug, Authentication authentication) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return collectionRepository.findBySlug(slug)
                .filter(row -> visibleTo(row, authentication))
                .map(CollectionReadService::toView);
    }

    /**
     * The collection registry's forward-only keyset scroll — the owning
     * sort field (the rendered NAME, with the {@code id} tiebreaker),
     * plus the VISIBLE registry total the Saleor {@code totalCount}
     * field carries (the publication law governs the count the same way
     * it governs the rows — never a leak by the total).
     */
    @Override
    @Transactional(readOnly = true)
    public CollectionPage scrollCollections(Authentication authentication,
                                            CollectionSort sort,
                                            KeysetScrollPosition position,
                                            Limit limit) {
        if (sort == null || sort.field() == null) {
            throw new IllegalArgumentException("A collection sort is required");
        }
        List<CollectionView> visible = new ArrayList<>();
        for (Collection row : collectionRepository.findAll()) {
            if (visibleTo(row, authentication)) {
                visible.add(toView(row));
            }
        }

        // The deterministic ordering — the owning sort field's TOTAL
        // ordering key with the id tiebreaker, both in the requested
        // direction (the roster's SQL keyset methods' own shape).
        Comparator<String> fieldOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<UUID> idOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Function<CollectionView, String> fieldExtractor = CollectionReadService::orderingName;
        visible.sort(Comparator
                .comparing(fieldExtractor, fieldOrder)
                .thenComparing(CollectionView::id, idOrder));

        // The keyset predicate: elements strictly AFTER the cursor's own
        // (sortProperty, id) sort key in the requested direction.
        String sortProperty = sort.property();
        List<CollectionView> scrollable = visible;
        if (position != null && !position.isInitial()) {
            requireForwardKeyset(position, sortProperty, "collection");
            String cursorField = String.valueOf(
                    ((KeysetScrollPosition) position).getKeys().get(sortProperty));
            UUID cursorId = parseCursorId(position, "collection");
            scrollable = visible.stream()
                    .filter(collection -> {
                        int byField = fieldOrder.compare(
                                fieldExtractor.apply(collection), cursorField);
                        return byField != 0 ? byField > 0
                                : idOrder.compare(collection.id(), cursorId) > 0;
                    })
                    .toList();
        }

        int pageSize = limit == null || !limit.isLimited()
                ? scrollable.size()
                : Math.min(limit.max(), scrollable.size());
        boolean hasNext = scrollable.size() > pageSize;
        List<CollectionView> content = scrollable.subList(0, pageSize);
        Window<CollectionView> window = Window.from(content, index ->
                ScrollPosition.forward(Map.of(
                        sortProperty, fieldExtractor.apply(content.get(index)),
                        "id", content.get(index).id())),
                hasNext);
        return new CollectionPage(window, visible.size());
    }

    /**
     * One collection's member-products scroll — the membership registry's
     * own (position, id) ordering key, forward-only, over the member
     * products the same module's {@code products} table owns. The
     * member-product publication law governs: a non-ADMIN caller's scroll
     * carries the published (ACTIVE) products alone, and the total
     * answers the same visibility.
     */
    @Override
    @Transactional(readOnly = true)
    public CollectionProductsPage scrollCollectionProducts(UUID collectionId,
                                                            KeysetScrollPosition position,
                                                            Limit limit,
                                                            Authentication authentication) {
        if (collectionId == null) {
            throw new IllegalArgumentException("A collection id is required");
        }
        boolean staff = staffCaller(authentication);
        List<CollectionProduct> membership =
                collectionProductRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId);
        List<ProductView> visible = new ArrayList<>(membership.size());
        for (CollectionProduct member : membership) {
            productRepository.findById(member.getProductId())
                    .filter(product -> staff || product.getStatus() == StoreProductStatus.ACTIVE)
                    .ifPresent(product -> visible.add(toProductView(product)));
        }

        // The deterministic ordering — the membership's own (position, id)
        // key, ascending only (the display order's own law; the Saleor
        // products field's forward-only pagination contract).
        Comparator<Integer> positionOrder = Comparator.naturalOrder();
        Comparator<UUID> idOrder = Comparator.naturalOrder();
        visible.sort(Comparator
                .comparing((ProductView product) -> positionOf(membership, product.id()), positionOrder)
                .thenComparing(ProductView::id, idOrder));

        List<ProductView> scrollable = visible;
        if (position != null && !position.isInitial()) {
            requireForwardKeyset(position, "position", "collection products");
            int cursorPosition = parseCursorInt(position, "collection products");
            UUID cursorId = parseCursorId(position, "collection products");
            scrollable = visible.stream()
                    .filter(product -> {
                        int byPosition = Integer.compare(
                                positionOf(membership, product.id()), cursorPosition);
                        return byPosition != 0 ? byPosition > 0
                                : idOrder.compare(product.id(), cursorId) > 0;
                    })
                    .toList();
        }

        int pageSize = limit == null || !limit.isLimited()
                ? scrollable.size()
                : Math.min(limit.max(), scrollable.size());
        boolean hasNext = scrollable.size() > pageSize;
        List<ProductView> content = scrollable.subList(0, pageSize);
        Window<ProductView> window = Window.from(content, index ->
                ScrollPosition.forward(Map.of(
                        "position", positionOf(membership, content.get(index).id()),
                        "id", content.get(index).id())),
                hasNext);
        return new CollectionProductsPage(window, visible.size());
    }

    /**
     * The publication law — the pinned include-unpublished permission
     * family mapped to the platform's ADMIN capability over the received
     * {@code Authentication} (the frozen authorization contract: enforced
     * at the owning use-case boundary, never only in a resolver).
     */
    private static boolean visibleTo(Collection row, Authentication authentication) {
        return row.isPublished() || staffCaller(authentication);
    }

    /** The ADMIN-class caller check — the platform's own capability mapping. */
    private static boolean staffCaller(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The non-initial position contract — a forward keyset over the
     * executed sort's own (property, id) key set, or the honest
     * {@link IllegalArgumentException} (never a silent no-op, never a
     * fallback to another position).
     */
    private static void requireForwardKeyset(KeysetScrollPosition position,
                                             String sortProperty,
                                             String scrollName) {
        if (!(position instanceof KeysetScrollPosition keyset)
                || !keyset.scrollsForward()
                || keyset.getKeys().get(sortProperty) == null
                || keyset.getKeys().get("id") == null) {
            throw new IllegalArgumentException("The " + scrollName
                    + " scroll position must be a forward keyset over (" + sortProperty + ", id)");
        }
    }

    /** The cursor's id key — parsed under the same classified error family. */
    private static UUID parseCursorId(KeysetScrollPosition position, String scrollName) {
        Object raw = ((KeysetScrollPosition) position).getKeys().get("id");
        try {
            return raw instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(raw));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("The " + scrollName
                    + " scroll position must be a forward keyset with a parseable id", malformed);
        }
    }

    /** The cursor's position key — the membership scroll's own int-typed key. */
    private static int parseCursorInt(KeysetScrollPosition position, String scrollName) {
        Object raw = ((KeysetScrollPosition) position).getKeys().get("position");
        try {
            return raw instanceof Integer number ? number : Integer.parseInt(String.valueOf(raw));
        } catch (IllegalArgumentException | ClassCastException malformed) {
            throw new IllegalArgumentException("The " + scrollName
                    + " scroll position must be a forward keyset with a parseable position",
                    malformed instanceof RuntimeException runtime ? runtime
                            : new IllegalArgumentException(String.valueOf(malformed)));
        }
    }

    /** The member's own position fact from the membership registry. */
    private static int positionOf(List<CollectionProduct> membership, UUID productId) {
        for (CollectionProduct member : membership) {
            if (member.getProductId().equals(productId)) {
                return member.getPosition();
            }
        }
        // The visible member always holds a membership row (the scroll's
        // own source); the unreachable default keeps the comparator total.
        return Integer.MAX_VALUE;
    }

    /**
     * The row's TOTAL ordering key for the NAME sort — the rendered
     * primary display name, with the empty string for a row carrying
     * neither bilingual name (deterministic and non-null for the keyset
     * positions; the wire name stays the honest null).
     */
    private static String orderingName(CollectionView view) {
        return view.name() != null ? view.name() : "";
    }

    /**
     * The bounded collection projection — only facts the {@code collections}
     * row owns: the rendered primary display name ({@code nameEn} when
     * present, else {@code nameAr}), the bilingual second name, the stable
     * public slug, and the row's own publication fact.
     */
    private static CollectionView toView(Collection collection) {
        String name = collection.getNameEn() != null ? collection.getNameEn() : collection.getNameAr();
        return new CollectionView(collection.getId(), name, collection.getNameAr(),
                collection.getSlug(), collection.isPublished());
    }

    /**
     * The member product's bounded projection — the products table's own
     * rendering law (the {@code ProductReadService} bounded projection's
     * own fields: the native id, the display title, the optional goods
     * schema reference, the display lifecycle state).
     */
    private static ProductView toProductView(Product product) {
        return new ProductView(product.getId(), product.getTitle(),
                product.getProductTypeId(), product.getStatus().name());
    }
}
