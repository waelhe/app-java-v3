package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.Category;
import com.marketplace.app.graphql.generated.types.CategoryCountableConnection;
import com.marketplace.app.graphql.generated.types.CategoryCountableEdge;
import com.marketplace.app.graphql.generated.types.CategoryFilterInput;
import com.marketplace.app.graphql.generated.types.CategorySortField;
import com.marketplace.app.graphql.generated.types.CategorySortingInput;
import com.marketplace.app.graphql.generated.types.CategoryWhereInput;
import com.marketplace.app.graphql.generated.types.OrderDirection;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.catalog.spi.CategoryPage;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategorySort;
import com.marketplace.catalog.spi.CategoryView;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Products-domain {@code category} and {@code categories} read
 * roots, packet campaign-b4-a — over the catalog owner's use cases
 * through the declared {@code CategoryReadSpi} seam. This class owns
 * ONLY transport mapping: global-ID decode/encode, argument validation,
 * cursor/position mapping and the connection envelope. Authorization
 * lives in the owning use case — these are Saleor PUBLIC storefront
 * roots (no permission for published content; the platform registry
 * carries no publication flag, so the include-unpublished surface is
 * not applicable — the registered public-root mapping divergence): the
 * platform's authentication boundary for this surface is the
 * {@code /graphql} chain itself, never a UI route guard and never
 * duplicated here.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code category} executes its {@code id} argument through the typed
 * global-ID codec and its {@code slug} argument through the owner's
 * code lookup (upstream's id-first precedence between the two
 * EXECUTABLE keys); {@code categories} executes forward-only keyset
 * paging ({@code first}/{@code after}) in the one sort with an owning
 * fact (NAME — deterministic with the {@code id} tiebreaker) and
 * answers explicit classified errors for the declared wire arguments
 * whose owning capability does not exist yet ({@code filter},
 * {@code where}, {@code level} (the flat registry owns no tree), the
 * unowned {@code sortBy} fields (PRODUCT_COUNT/SUBCATEGORY_COUNT), and
 * {@code before}/{@code last}, plus the {@code category} root's
 * {@code slugLanguageCode} (no translation owner) and
 * {@code externalReference} (no external-ID column) and its keyless
 * call shape) — recorded divergences, never silent no-ops and never
 * fake successes.</p>
 */
@DgsComponent
public class CategoryDataFetcher {

    /**
     * The default category sort when the caller sends none — the
     * rendered display-name ordering ascending (the product-type and
     * attribute slices' own default), deterministic with the
     * {@code id} tiebreaker.
     */
    private static final CategorySort DEFAULT_SORT =
            CategorySort.of(CategorySort.Field.NAME, true);

    private final CategoryReadSpi categoryReadSpi;
    private final CategoryGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public CategoryDataFetcher(CategoryReadSpi categoryReadSpi,
                               CategoryGraphQlMapper mapper,
                               GraphQlProperties properties,
                               EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                               SaleorGlobalIdCodec globalIdCodec) {
        this.categoryReadSpi = categoryReadSpi;
        this.mapper = mapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code categories(...): CategoryCountableConnection} — the
     * listing-category registry as a forward-only keyset scroll (the
     * staffUsers/permissionGroups/productTypes/attributes keyset
     * precedent: cursors identify a row's sort key, never an absolute
     * offset) inside Saleor's exact countable-connection envelope,
     * including {@code totalCount}.
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): the boundary is
     * tested, recorded in the divergence registry, and never a silent
     * no-op — {@code filter} and {@code where} (no owning query
     * capability), {@code level} (the flat registry owns no nesting
     * tree), the unowned sort fields, and the backward pagination. An
     * empty or non-positive {@code first} answers the empty page (the
     * staffUsers field's own house contract), so {@code totalCount}
     * answers the honest not-computed null — never a fabricated zero
     * total.</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.Categories)
    @RateLimiter(name = "graphqlCategoryQuery")
    public CategoryCountableConnection categories(
            @InputArgument Integer first,
            @InputArgument String after,
            @InputArgument String before,
            @InputArgument Integer last,
            @InputArgument CategorySortingInput sortBy,
            @InputArgument CategoryFilterInput filter,
            @InputArgument CategoryWhereInput where,
            @InputArgument Integer level) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "categories filter is not implemented on this platform yet");
        }
        if (where != null) {
            throw new IllegalArgumentException(
                    "categories where filter is not implemented on this platform yet");
        }
        if (level != null) {
            throw new IllegalArgumentException(
                    "categories level filtering is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "categories supports forward-only pagination (first/after) on this platform");
        }

        CategorySort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return CategoryCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = keysetPosition(after, sort.property());
        CategoryPage page = categoryReadSpi.scrollCategories(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * Saleor {@code category(id: ID, slug: String, slugLanguageCode:
     * LanguageCodeEnum, externalReference: String): Category} — the
     * public storefront lookup over the registry. The {@code id}
     * argument executes through the typed global-ID codec: an unknown
     * but well-formed lookup answers null (the safe absence); a
     * malformed or mistyped global ID answers the codec's classified
     * validation error — never a silent null and never a raw UUID
     * fallback. The {@code slug} argument executes through the owner's
     * code lookup: an unknown but well-formed slug likewise answers
     * null. When both executable keys are sent, {@code id} wins —
     * upstream's own documented precedence. The
     * {@code slugLanguageCode} argument (no translation owner) and the
     * {@code externalReference} argument (no external-ID column) have
     * no owning capability on this platform and answer the explicit
     * classified error whenever they are sent; a call with no key at
     * all answers the classified validation error (a keyless lookup is
     * a client error here, never a fabricated absence — the recorded
     * divergence from upstream's null answer).
     */
    @DgsQuery(field = DgsConstants.QUERY.Category)
    @RateLimiter(name = "graphqlCategoryQuery")
    public Category category(@InputArgument String id,
                             @InputArgument String slug,
                             @InputArgument String slugLanguageCode,
                             @InputArgument String externalReference) {
        Authentication authentication = currentAuthentication();
        if (slugLanguageCode != null) {
            throw new IllegalArgumentException(
                    "category slugLanguageCode lookup is not implemented on this platform yet");
        }
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "category externalReference lookup is not implemented on this platform yet");
        }
        if (id == null && slug == null) {
            throw new IllegalArgumentException(
                    "category requires one of id or slug on this platform");
        }
        if (id != null) {
            UUID nativeId = globalIdCodec.decode(id, CategoryGraphQlMapper.CATEGORY_TYPE);
            return categoryReadSpi.findCategoryById(nativeId, authentication)
                    .map(mapper::toGraphQlCategory)
                    .orElse(null);
        }
        return categoryReadSpi.findCategoryBySlug(slug, authentication)
                .map(mapper::toGraphQlCategory)
                .orElse(null);
    }

    /**
     * The sort mapping — Saleor's {@code CategorySortingInput} onto the
     * owner's executed keyset sort. NAME (the rendered display name)
     * has an owning fact and executes; PRODUCT_COUNT and
     * SUBCATEGORY_COUNT (a products connection and a category TREE the
     * flat registry does not own) answer the explicit not-supported
     * error — the staffUsers boundary's own classified-error contract,
     * never a silent fallback to another sort.
     */
    private static CategorySort toOwnerSort(CategorySortingInput sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        boolean ascending = sortBy.getDirection() == null || OrderDirection.ASC.equals(sortBy.getDirection());
        CategorySortField field = sortBy.getField() == null
                ? CategorySortField.NAME
                : sortBy.getField();
        return switch (field) {
            case NAME -> CategorySort.of(CategorySort.Field.NAME, ascending);
            case PRODUCT_COUNT, SUBCATEGORY_COUNT -> throw new IllegalArgumentException(
                    "categories sort field " + field + " is not implemented on this platform yet");
        };
    }

    /**
     * The ONE shared cursor gate (the house KeysetCursorGate): the key set
     * is the executed sort's own (property, id) pair with the property's
     * declared value type, so a client-crafted cursor with well-named but
     * unparseable values — or a cursor emitted by the OTHER sort — answers
     * the same VAL-001 taxonomy.
     */
    private KeysetScrollPosition keysetPosition(String after, String sortProperty) {
        return KeysetCursorGate.forwardKeyset(cursorStrategy, after, sortProperty,
                KeysetCursorGate::textValue, "categories with the same sortBy");
    }

    /**
     * The connection envelope — the declared (not runtime-generated)
     * Saleor shape, built from the Window's own per-element scroll
     * positions (the exact positions the framework's connection
     * adaptation emits for its generated connections — one cursor
     * strategy (toCursor/fromCursor), one cursor semantics across the
     * whole surface). {@code hasPreviousPage} is the forward-only
     * honest fact: a scroll that started after a known element has
     * elements before it. {@code totalCount} is the registry count
     * (GraphQL Int's signed 32-bit ceiling documented, hence the clamp).
     */
    private CategoryCountableConnection toConnection(CategoryPage page, KeysetScrollPosition position) {
        Window<CategoryView> window = page.window();
        List<CategoryView> content = window.getContent();
        List<CategoryCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(CategoryCountableEdge.newBuilder()
                    .node(mapper.toGraphQlCategory(content.get(i)))
                    .cursor(cursorStrategy.toCursor(window.positionAt(i)))
                    .build());
        }
        String startCursor = edges.isEmpty() ? null : edges.get(0).getCursor();
        String endCursor = edges.isEmpty() ? null : edges.get(edges.size() - 1).getCursor();
        PageInfo pageInfo = PageInfo.newBuilder()
                .hasNextPage(window.hasNext())
                .hasPreviousPage(!position.isInitial())
                .startCursor(startCursor)
                .endCursor(endCursor)
                .build();
        return CategoryCountableConnection.newBuilder()
                .edges(edges)
                .pageInfo(pageInfo)
                .totalCount((int) Math.min(page.total(), Integer.MAX_VALUE))
                .build();
    }

    /**
     * The authenticated caller — the house pattern (the /graphql chain
     * guarantees authentication; the guard keeps the fetcher honest if a
     * future public journey reopens the route policy).
     */
    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required for category reads");
        }
        return authentication;
    }
}
