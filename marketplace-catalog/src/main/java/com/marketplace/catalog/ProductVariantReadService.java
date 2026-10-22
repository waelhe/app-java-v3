package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductVariantPage;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductVariantSort;
import com.marketplace.catalog.spi.ProductVariantView;
import com.marketplace.catalog.spi.ProductView;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code productVariant}
 * and {@code productVariants} read roots, packet campaign-b5-a): the
 * owning use case for the store's product-variant population's read
 * surface. These are Saleor PUBLIC storefront roots whose
 * include-unpublished variant carries the
 * MANAGE_ORDERS/MANAGE_DISCOUNTS/MANAGE_PRODUCTS-class permission —
 * mapped through the platform's ADMIN capability (the
 * SaleorPermissionCatalog contract, enforced HERE at the owning
 * use-case boundary, never only in a resolver). The public population
 * is the TWO-ROW gate the platform's own reservation write carries
 * verbatim: the variant's ACTIVE shelf AND the parent product's
 * ACTIVE shelf (the {@code ProductVariantRepository.reserve}
 * predicate's read mirror — Saleor's own public-variant population is
 * the variants of published products); the ADMIN capability's scroll
 * and lookup widen to every state and every parent — the
 * permission-aware population mapping the registered divergence row
 * records.
 *
 * <p><b>The scroll is a REAL SQL keyset scroll</b> (the
 * {@code scrollStaffUsers} repository-keyset precedent): the
 * product_variants table is populated commerce data, never bounded
 * reference data. The ADMIN population rides the derived
 * {@code Window}-returning repository methods (the framework's own
 * keyset predicate, Hibernate's {@code @SoftDelete} filter applied);
 * the PUBLIC population rides the native keyset scrolls because its
 * two-row gate spans the plain {@code product_id} column — the SAME
 * native-SQL-over-two-rows discipline the repository's own
 * reservation write established. The service builds the public
 * path's {@link Window} from the native rows' own sort keys (the
 * positions the framework would have emitted — ONE cursor semantics
 * across both paths), fetching one row past the page so
 * {@code hasNext} is the database's own answer.</p>
 *
 * <p><b>Referential honesty:</b> the wire's non-null {@code product}
 * field renders the parent AS-IS (any lifecycle state — only the
 * VARIANT's readability is lifecycle-gated). A variant whose parent
 * row no longer resolves (a soft-deleted parent — the roster's own
 * cascade discipline) answers the safe absence: include-unpublished
 * widens lifecycle states, never referential integrity, and a null
 * {@code Product!} is never rendered.</p>
 */
@Service
public class ProductVariantReadService implements ProductVariantReadSpi {

    /** The platform's ADMIN authority name (the JWT converter's ROLE_ prefix contract). */
    static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;

    public ProductVariantReadService(ProductVariantRepository variantRepository,
                                     ProductRepository productRepository) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
    }

    /**
     * The variant lookup by the native UUID behind the typed global ID —
     * the permission-aware readable population: an unknown but
     * well-formed UUID answers empty; a KNOWN row outside the caller's
     * population — a non-ACTIVE variant, or an ACTIVE variant under a
     * non-ACTIVE parent — answers empty for non-ADMIN callers and is
     * readable only by the ADMIN capability — never an error and never
     * a leak of the unpublished surface.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ProductVariantView> findVariantById(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return variantRepository.findById(id)
                .filter(variant -> isAdmin(authentication) || isPubliclyReadable(variant))
                .flatMap(this::resolveView);
    }

    /**
     * The variant population's REAL SQL forward-only keyset scroll — the
     * caller's own population: the two-row-gated public population
     * (variant ACTIVE AND parent product ACTIVE) for non-ADMIN callers,
     * every live row for the ADMIN capability (the include-unpublished
     * variant). The page's total is the SAME population's count — never
     * another scope's.
     */
    @Override
    @Transactional(readOnly = true)
    public ProductVariantPage scrollVariants(Authentication authentication,
                                             ProductVariantSort sort,
                                             KeysetScrollPosition position,
                                             Limit limit) {
        if (sort == null || sort.field() == null) {
            throw new IllegalArgumentException("A product-variant sort is required");
        }
        if (limit == null || limit.value() <= 0) {
            throw new IllegalArgumentException("A positive page size is required");
        }
        if (isAdmin(authentication)) {
            return scrollAdminPopulation(sort, position, limit);
        }
        return scrollPublicPopulation(sort, position, limit);
    }

    // ------------------------------------------------------------------
    // The ADMIN population — the derived framework keyset scroll
    // ------------------------------------------------------------------

    private ProductVariantPage scrollAdminPopulation(ProductVariantSort sort,
                                                     KeysetScrollPosition position,
                                                     Limit limit) {
        Window<ProductVariant> window = switch (sort.field()) {
            case LAST_MODIFIED_AT -> sort.ascending()
                    ? variantRepository.findByOrderByUpdatedAtAscIdAsc(position, limit)
                    : variantRepository.findByOrderByUpdatedAtDescIdDesc(position, limit);
            case ID -> sort.ascending()
                    ? variantRepository.findByOrderByIdAsc(position, limit)
                    : variantRepository.findByOrderByIdDesc(position, limit);
        };
        List<ProductVariant> content = window.getContent();
        Map<UUID, ProductView> parents = parentProjections(content);
        List<ProductVariantView> views = new ArrayList<>(content.size());
        List<ScrollPosition> positions = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            ProductVariant row = content.get(i);
            ProductView parent = parents.get(row.getProductId());
            if (parent == null) {
                // The referential-honesty stance: a roster row whose parent
                // no longer resolves is never rendered as a null Product! —
                // include-unpublished widens lifecycle states, not integrity.
                continue;
            }
            views.add(toView(row, parent));
            positions.add(window.positionAt(i));
        }
        Window<ProductVariantView> result = Window.from(views, positions::get, window.hasNext());
        return new ProductVariantPage(result, variantRepository.count());
    }

    // ------------------------------------------------------------------
    // The PUBLIC population — the native two-row-gated keyset scroll
    // ------------------------------------------------------------------

    private ProductVariantPage scrollPublicPopulation(ProductVariantSort sort,
                                                      KeysetScrollPosition position,
                                                      Limit limit) {
        OffsetDateTime lastUpdatedAt = lastUpdatedAt(sort, position);
        UUID lastId = lastId(sort, position);
        int fetchCount = limit.value() + 1;
        List<ProductVariant> rows = switch (sort.field()) {
            case LAST_MODIFIED_AT -> sort.ascending()
                    ? variantRepository.scrollPublicByUpdatedAtAsc(lastUpdatedAt, lastId, fetchCount)
                    : variantRepository.scrollPublicByUpdatedAtDesc(lastUpdatedAt, lastId, fetchCount);
            case ID -> sort.ascending()
                    ? variantRepository.scrollPublicByIdAsc(lastId, fetchCount)
                    : variantRepository.scrollPublicByIdDesc(lastId, fetchCount);
        };
        boolean hasNext = rows.size() > limit.value();
        List<ProductVariant> content =
                hasNext ? new ArrayList<>(rows.subList(0, limit.value())) : rows;
        Map<UUID, ProductView> parents = parentProjections(content);
        List<ProductVariantView> views = new ArrayList<>(content.size());
        List<ScrollPosition> positions = new ArrayList<>(content.size());
        for (ProductVariant row : content) {
            ProductView parent = parents.get(row.getProductId());
            if (parent == null) {
                // Cannot happen behind the native EXISTS gate (the live
                // ACTIVE parent is part of the population predicate itself)
                // — the belt-and-braces stance the ADMIN path documents.
                continue;
            }
            views.add(toView(row, parent));
            positions.add(keysetPosition(sort, row));
        }
        Window<ProductVariantView> window = Window.from(views, positions::get, hasNext);
        return new ProductVariantPage(window, variantRepository.countPublicPopulation());
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
     * The public population's two-row gate — the reserve write's own
     * predicate as a read: the variant's ACTIVE shelf AND the parent
     * product's ACTIVE shelf.
     */
    private boolean isPubliclyReadable(ProductVariant variant) {
        return variant.getStatus() == ProductStatus.ACTIVE
                && productRepository.findById(variant.getProductId())
                        .map(parent -> parent.getStatus() == StoreProductStatus.ACTIVE)
                        .orElse(false);
    }

    /**
     * The lookup's projection — the parent renders AS-IS (any lifecycle
     * state); a parent row that no longer resolves answers the safe
     * absence (never a null {@code Product!}).
     */
    private Optional<ProductVariantView> resolveView(ProductVariant variant) {
        return productRepository.findById(variant.getProductId())
                .map(parent -> toView(variant, toProductView(parent)));
    }

    /**
     * The bounded projections — only facts the {@code product_variants}
     * row owns (plus the parent's own bounded projection).
     */
    private static ProductVariantView toView(ProductVariant variant, ProductView parent) {
        return new ProductVariantView(variant.getId(), variant.getName(), variant.getSku(),
                variant.getStatus().name(), parent);
    }

    private static ProductView toProductView(Product product) {
        return new ProductView(product.getId(), product.getTitle(),
                product.getProductTypeId(), product.getStatus().name());
    }

    /**
     * The parent projections for one page — ONE batched
     * {@code findAllById} over the page's distinct parent ids (the
     * classic batched-association load; never a per-row N+1).
     */
    private Map<UUID, ProductView> parentProjections(List<ProductVariant> content) {
        if (content.isEmpty()) {
            return Map.of();
        }
        Set<UUID> parentIds = new java.util.HashSet<>();
        for (ProductVariant row : content) {
            parentIds.add(row.getProductId());
        }
        Map<UUID, ProductView> parents = new HashMap<>(parentIds.size() * 2);
        for (Product parent : productRepository.findAllById(parentIds)) {
            parents.put(parent.getId(), toProductView(parent));
        }
        return parents;
    }

    /**
     * The public path's per-row keyset position — the row's own sort keys
     * in the SAME vocabulary the framework's windows emit ({@code (updatedAt, id)}
     * or the unique {@code id} alone), so the boundary's ONE cursor
     * strategy encodes both paths identically.
     */
    private static ScrollPosition keysetPosition(ProductVariantSort sort, ProductVariant row) {
        if (sort.field() == ProductVariantSort.Field.LAST_MODIFIED_AT) {
            return ScrollPosition.forward(Map.of(
                    "updatedAt", row.getUpdatedAt(),
                    "id", row.getId()));
        }
        return ScrollPosition.forward(Map.of("id", row.getId()));
    }

    /**
     * The public path's {@code after}-cursor bind — the executed sort's
     * own {@code updatedAt} key as the timestamptz-native bind value
     * (the gate already normalized the decoded value to
     * {@link Instant}); null on the initial page.
     */
    private static OffsetDateTime lastUpdatedAt(ProductVariantSort sort, KeysetScrollPosition position) {
        if (sort.field() != ProductVariantSort.Field.LAST_MODIFIED_AT || position.isInitial()) {
            return null;
        }
        Object raw = position.getKeys().get("updatedAt");
        return raw instanceof Instant instant
                ? instant.atOffset(ZoneOffset.UTC)
                : OffsetDateTime.parse(String.valueOf(raw));
    }

    /**
     * The public path's {@code after}-cursor id bind — the unique
     * tiebreaker (or the whole keyset for the ID sort); null on the
     * initial page.
     */
    private static UUID lastId(ProductVariantSort sort, KeysetScrollPosition position) {
        if (position.isInitial()) {
            return null;
        }
        Object raw = position.getKeys().get("id");
        return raw instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(raw));
    }
}
