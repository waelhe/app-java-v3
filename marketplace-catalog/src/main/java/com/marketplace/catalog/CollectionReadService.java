package com.marketplace.catalog;

import com.marketplace.catalog.spi.CollectionPage;
import com.marketplace.catalog.spi.CollectionProductsPage;
import com.marketplace.catalog.spi.CollectionReadSpi;
import com.marketplace.catalog.spi.CollectionSort;
import com.marketplace.catalog.spi.CollectionView;
import com.marketplace.catalog.spi.ProductView;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    /**
     * B8 (CAT-03): the member-products scroll's page ceiling when the
     * caller does not scope one — the platform's documented
     * bounded-collection hard maximum (100 rows: "bounds database work,
     * heap and payload regardless of what a client asks for"), so the
     * unlimited seam can never degrade back into the whole-membership
     * load the review measured.
     */
    static final int DEFAULT_MEMBERSHIP_PAGE_MAX = 100;

    private final CollectionRepository collectionRepository;
    private final CollectionProductRepository collectionProductRepository;

    public CollectionReadService(CollectionRepository collectionRepository,
                                 CollectionProductRepository collectionProductRepository) {
        this.collectionRepository = collectionRepository;
        this.collectionProductRepository = collectionProductRepository;
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
        // The 2026-11-08 #808 round-24 review (the body's unverified
        // finding, verified against the code — the scrollCollectionProducts
        // and scrollVariants guards' own law): a BOUNDED non-positive page
        // size is refused up front — a zero limit with visible rows cut an
        // EMPTY window that still advertised hasNext=true with no element
        // from which to obtain a continuation cursor (the client either
        // stopped prematurely or repeated the same empty page forever).
        // The null/unlimited case stays the caller's own whole-registry
        // read (this in-memory scroll loads the registry either way).
        if (limit != null && limit.isLimited() && limit.max() <= 0) {
            throw new IllegalArgumentException("A positive page size is required");
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
     *
     * <p><b>B8 (CAT-03 — the 2026-11-03 adversarial review): the page is
     * cut in SQL, never in memory.</b> The scroll previously loaded the
     * whole live membership and issued one {@code findById} per member
     * before sorting and slicing in memory; it now answers ONE bounded
     * joined statement per page (the membership row and its product in
     * the same query, the publication law filtered by that same query)
     * plus ONE count statement for the honest {@code totalCount}. The
     * membership's live positions are unique (the V298 partial unique
     * index), so the (position, product id) key is a total ordering —
     * the ORDER BY alone answers the deterministic order the in-memory
     * comparator used to establish. An unlimited request answers the
     * platform's bounded-collection default page (the documented 100-row
     * hard ceiling that "bounds database work, heap and payload"),
     * never the unbounded whole-membership load.</p>
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
        int pageSize = resolvePageSize(limit);
        boolean staff = staffCaller(authentication);
        // The wave-19 #808 review's publication gate, enforced before the
        // membership registry is ever consulted: a non-ADMIN caller's
        // scroll over an unknown or unpublished collection answers the
        // same safe empty page the lookup answers (the visibleTo law's
        // own row filter) — never a product exposure through a collection
        // the same caller cannot read, and never a registry probe that
        // would disclose the shelf's existence.
        if (!staff && !collectionRepository.findById(collectionId)
                .map(row -> visibleTo(row, authentication))
                .orElse(false)) {
            return new CollectionProductsPage(
                    Window.from(List.<ProductView>of(), index ->
                            ScrollPosition.forward(Map.of("position", 0)), false), 0);
        }
        Integer afterPosition = null;
        UUID afterId = null;
        if (position != null && !position.isInitial()) {
            requireForwardKeyset(position, "position", "collection products");
            afterPosition = parseCursorInt(position, "collection products");
            afterId = parseCursorId(position, "collection products");
        }
        // The one-beyond probe row answers hasNext in the same statement
        // (the page query's own Limit discipline — no second lookahead).
        List<Object[]> rows = afterPosition == null
                ? collectionProductRepository.findFirstMembershipProductRows(
                        collectionId, staff, PageRequest.of(0, pageSize + 1))
                : collectionProductRepository.findNextMembershipProductRows(
                        collectionId, staff, afterPosition, afterId,
                        PageRequest.of(0, pageSize + 1));
        boolean hasNext = rows.size() > pageSize;
        List<Object[]> content = hasNext ? rows.subList(0, pageSize) : rows;
        List<ProductView> visible = new ArrayList<>(content.size());
        Map<UUID, Integer> memberPositions = new HashMap<>(content.size() * 2);
        for (Object[] row : content) {
            Product product = (Product) row[1];
            memberPositions.put(product.getId(), (Integer) row[0]);
            visible.add(toProductView(product));
        }
        Window<ProductView> window = Window.from(visible, index ->
                ScrollPosition.forward(Map.of(
                        "position", memberPositions.get(visible.get(index).id()),
                        "id", visible.get(index).id())),
                hasNext);
        return new CollectionProductsPage(
                window, collectionProductRepository.countVisibleMembershipProducts(collectionId, staff));
    }

    /**
     * The page size law — the sibling {@code ProductReadService} guard's
     * own contract: a positive page size is REQUIRED (an unbounded page
     * would replace the SQL keyset with an unbounded load — the exact
     * CAT-03 shape), and an unlimited request answers the platform's
     * documented bounded-collection ceiling (100 rows) instead.
     */
    private static int resolvePageSize(Limit limit) {
        if (limit == null || !limit.isLimited() || limit.max() >= Integer.MAX_VALUE) {
            return DEFAULT_MEMBERSHIP_PAGE_MAX;
        }
        if (limit.max() <= 0) {
            throw new IllegalArgumentException("A positive page size is required");
        }
        // The 2026-11-05 #808 round (Minor · Performance & Scalability):
        // the caller's bounded limit is itself CAPPED at the documented
        // membership ceiling — the previous shape returned limit.max()
        // unbounded, so a Limit.of(100_000) replaced the SQL keyset with
        // a mega-page (the exact CAT-03 shape the ceiling exists to
        // prevent).
        return Math.min(limit.max(), DEFAULT_MEMBERSHIP_PAGE_MAX);
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
