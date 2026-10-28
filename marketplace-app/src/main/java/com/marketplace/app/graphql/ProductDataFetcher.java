package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.Product;
import com.marketplace.app.graphql.generated.types.ProductCountableConnection;
import com.marketplace.app.graphql.generated.types.ProductCountableEdge;
import com.marketplace.app.graphql.generated.types.ProductFilterInput;
import com.marketplace.app.graphql.generated.types.ProductOrder;
import com.marketplace.app.graphql.generated.types.ProductOrderField;
import com.marketplace.app.graphql.generated.types.ProductWhereInput;
import com.marketplace.app.graphql.generated.types.ProductType;
import com.marketplace.app.graphql.generated.types.OrderDirection;
import com.marketplace.catalog.spi.ProductPage;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductSort;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductView;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import graphql.schema.DataFetchingEnvironment;
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
 * Saleor Products-domain {@code product} and {@code products} read
 * roots, packet campaign-b4-c — over the catalog owner's use cases
 * through the declared {@code ProductReadSpi} seam. This class owns
 * ONLY transport mapping: global-ID decode/encode, argument validation,
 * cursor/position mapping and the connection envelope. Authorization
 * lives in the owning use case: these are Saleor PUBLIC storefront
 * roots whose include-unpublished variant maps to the platform's ADMIN
 * capability (the SaleorPermissionCatalog contract) — the public
 * population is the ACTIVE lifecycle slice, the ADMIN caller's
 * scroll/lookup widens to every state (the registered permission-aware
 * population mapping divergence) — never in a UI route guard and never
 * duplicated here.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code product} executes its {@code id} argument through the typed
 * global-ID codec (a non-ADMIN caller's lookup of an unpublished row
 * answers the safe null — the data-scope boundary the owning use case
 * enforces); {@code products} executes forward-only keyset paging
 * ({@code first}/{@code after}) in the one sort with an owning keyset
 * fact (NAME — the title column, deterministic with the {@code id}
 * tiebreaker, REAL SQL keyset scrolling) and answers explicit
 * classified errors for the declared wire arguments whose owning
 * capability does not exist yet ({@code filter}, {@code where},
 * {@code search}, {@code channel}, the {@code attributeId} leg of
 * {@code sortBy} and its thirteen unowned sort fields, and
 * {@code before}/{@code last}, plus the {@code product} root's
 * {@code slug} (no slug column), {@code slugLanguageCode} (no
 * translation owner), {@code externalReference} (no external-ID
 * column), {@code channel} (no channel model — the PENDING-ADR
 * commercial-context row) and its keyless call shape) — recorded
 * divergences, never silent no-ops and never fake successes.</p>
 */
@DgsComponent
public class ProductDataFetcher {

    /**
     * The default product sort when the caller sends none — the display
     * title ordering ascending (the product-type/attribute/category
     * slices' own default), deterministic with the {@code id}
     * tiebreaker.
     */
    private static final ProductSort DEFAULT_SORT =
            ProductSort.of(ProductSort.Field.NAME, true);

    private final ProductReadSpi productReadSpi;
    private final ProductGraphQlMapper mapper;
    private final ProductTypeReadSpi productTypeReadSpi;
    private final ProductTypeGraphQlMapper productTypeMapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public ProductDataFetcher(ProductReadSpi productReadSpi,
                              ProductGraphQlMapper mapper,
                              ProductTypeReadSpi productTypeReadSpi,
                              ProductTypeGraphQlMapper productTypeMapper,
                              GraphQlProperties properties,
                              EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                              SaleorGlobalIdCodec globalIdCodec) {
        this.productReadSpi = productReadSpi;
        this.mapper = mapper;
        this.productTypeReadSpi = productTypeReadSpi;
        this.productTypeMapper = productTypeMapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code products(...): ProductCountableConnection} — the
     * store's product population as a REAL SQL forward-only keyset
     * scroll (the staffUsers keyset precedent: cursors identify a row's
     * sort key, never an absolute offset) inside Saleor's exact
     * countable-connection envelope, including {@code totalCount} — the
     * CALLER-SCOPE population's own count (the ACTIVE slice for
     * non-ADMIN callers, every state for the ADMIN capability).
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): the boundary is
     * tested, recorded in the divergence registry, and never a silent
     * no-op — {@code filter} and {@code where} (no owning query
     * capability), {@code search} (no owning search capability),
     * {@code channel} (no channel model), the backward pagination, the
     * {@code attributeId} sort leg and the thirteen unowned sort fields.
     * An empty or non-positive {@code first} answers the empty page (the
     * staffUsers field's own house contract), so {@code totalCount}
     * answers the honest not-computed null — never a fabricated zero
     * total.</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.Products)
    @RateLimiter(name = "graphqlProductQuery")
    public ProductCountableConnection products(
            @InputArgument Integer first,
            @InputArgument String after,
            @InputArgument String before,
            @InputArgument Integer last,
            @InputArgument ProductOrder sortBy,
            @InputArgument ProductFilterInput filter,
            @InputArgument ProductWhereInput where,
            @InputArgument String search,
            @InputArgument String channel) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "products filter is not implemented on this platform yet");
        }
        if (where != null) {
            throw new IllegalArgumentException(
                    "products where filter is not implemented on this platform yet");
        }
        if (search != null) {
            throw new IllegalArgumentException(
                    "products search is not implemented on this platform yet");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "products channel filtering is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "products supports forward-only pagination (first/after) on this platform");
        }

        ProductSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return ProductCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = keysetPosition(after, sort.property());
        ProductPage page = productReadSpi.scrollProducts(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * Saleor {@code product(id: ID, slug: String, slugLanguageCode:
     * LanguageCodeEnum, externalReference: String, channel: String):
     * Product} — the storefront lookup over the owning use case's
     * permission-aware readable population. The {@code id} argument
     * executes through the typed global-ID codec: an unknown but
     * well-formed lookup answers null (the safe absence); a KNOWN but
     * unpublished row answers null for non-ADMIN callers (the data-scope
     * boundary the owning use case enforces); a malformed or mistyped
     * global ID answers the codec's classified validation error — never
     * a silent null and never a raw UUID fallback. The {@code slug},
     * {@code slugLanguageCode}, {@code externalReference} and
     * {@code channel} arguments have no owning capability on this
     * platform and answer the explicit classified error whenever they
     * are sent; a call with no key at all answers the classified
     * validation error (a keyless lookup is a client error here, never
     * a fabricated absence — the recorded divergence from upstream's
     * null answer).
     */
    @DgsQuery(field = DgsConstants.QUERY.Product)
    @RateLimiter(name = "graphqlProductQuery")
    public Product product(@InputArgument String id,
                           @InputArgument String slug,
                           @InputArgument String slugLanguageCode,
                           @InputArgument String externalReference,
                           @InputArgument String channel) {
        Authentication authentication = currentAuthentication();
        if (slug != null) {
            throw new IllegalArgumentException(
                    "product slug lookup is not implemented on this platform yet");
        }
        if (slugLanguageCode != null) {
            throw new IllegalArgumentException(
                    "product slugLanguageCode lookup is not implemented on this platform yet");
        }
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "product externalReference lookup is not implemented on this platform yet");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "product channel filtering is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException(
                    "product requires its id on this platform");
        }
        UUID nativeId = globalIdCodec.decode(id, ProductGraphQlMapper.PRODUCT_TYPE);
        return productReadSpi.findProductById(nativeId, authentication)
                .map(mapper::toGraphQlProduct)
                .orElse(null);
    }

    /**
     * Saleor {@code Product.productType: ProductType} — the object-typed
     * reference (the G4 review on PR #723: the field used to render the
     * bare typed global ID, so subfield selections like {@code id} and
     * {@code name} could not work; upstream Saleor's own
     * {@code Product.productType} is the object). The mapper's Product
     * carries the reference stub — the same encoded product-type global
     * ID the field rendered before — and this fetcher resolves the FULL
     * bounded ProductType projection from the product-type owner through
     * the declared {@code ProductTypeReadSpi} seam (the productType read
     * root's own mapper and authorization boundary).
     *
     * <p>The fetcher runs ONLY when the field is selected (the engine's
     * own execution semantics), so the per-object owner lookup rides
     * every product that actually asks for its type — bounded by the
     * pagination hard maximum on list fields and single-object on the
     * {@code product} root. The nulls stay honest: no reference on the
     * product (pre-C1/zero-variant legacy rows) answers null WITHOUT an
     * owner call, and a referenced type the caller cannot see answers
     * the owner's own safe absence (null object, never a fabricated
     * one).</p>
     */
    // parentType/field carry the SDL's own names as literals: the codegen's
    // DgsConstants cover the root operation fields (@DgsQuery/@DgsMutation),
    // and an entity-field fetcher binds by the schema's own strings — a
    // rename here fails the real-SDL productType selections in the DGS and
    // integration suites immediately, never silently.
    @DgsData(parentType = "Product", field = "productType")
    @RateLimiter(name = "graphqlCatalogRead")
    public ProductType productType(DataFetchingEnvironment environment) {
        Product source = environment.getSource();
        if (source.getProductType() == null) {
            return null;
        }
        String reference = source.getProductType().getId();
        if (reference == null) {
            return null;
        }
        UUID nativeId = globalIdCodec.decode(reference, ProductTypeGraphQlMapper.PRODUCT_TYPE_TYPE);
        return productTypeReadSpi.findProductTypeById(nativeId, currentAuthentication())
                .map(productTypeMapper::toGraphQlProductType)
                .orElse(null);
    }

    /**
     * The sort mapping — Saleor's {@code ProductOrder} onto the owner's
     * executed keyset sort. NAME (the product's own title column) has an
     * owning keyset fact and executes; the {@code attributeId} leg and
     * the thirteen unowned fields (the search/channel/audit/publication/
     * collection/rating facts the platform does not own as sort keys)
     * answer the explicit not-supported error — the staffUsers
     * boundary's own classified-error contract, never a silent fallback
     * to another sort.
     */
    private static ProductSort toOwnerSort(ProductOrder sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        if (sortBy.getAttributeId() != null) {
            throw new IllegalArgumentException(
                    "products attribute sort is not implemented on this platform yet");
        }
        boolean ascending = sortBy.getDirection() == null || OrderDirection.ASC.equals(sortBy.getDirection());
        ProductOrderField field = sortBy.getField() == null
                ? ProductOrderField.NAME
                : sortBy.getField();
        return switch (field) {
            case NAME -> ProductSort.of(ProductSort.Field.NAME, ascending);
            case RANK, PRICE, MINIMAL_PRICE, LAST_MODIFIED, DATE, TYPE, PUBLISHED,
                 PUBLICATION_DATE, PUBLISHED_AT, LAST_MODIFIED_AT, COLLECTION, RATING,
                 CREATED_AT -> throw new IllegalArgumentException(
                    "products sort field " + field + " is not implemented on this platform yet");
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
                KeysetCursorGate::textValue, "products with the same sortBy");
    }

    /**
     * The connection envelope — the declared (not runtime-generated)
     * Saleor shape, built from the Window's own per-element scroll
     * positions (the exact positions the framework's connection
     * adaptation emits for its generated connections — one cursor
     * strategy (toCursor/fromCursor), one cursor semantics across the
     * whole surface). {@code hasPreviousPage} is the forward-only
     * honest fact: a scroll that started after a known element has
     * elements before it. {@code totalCount} is the caller-scope
     * population's count (GraphQL Int's signed 32-bit ceiling
     * documented, hence the clamp).
     */
    private ProductCountableConnection toConnection(ProductPage page, KeysetScrollPosition position) {
        Window<ProductView> window = page.window();
        List<ProductView> content = window.getContent();
        List<ProductCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(ProductCountableEdge.newBuilder()
                    .node(mapper.toGraphQlProduct(content.get(i)))
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
        return ProductCountableConnection.newBuilder()
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
                    "Authentication is required for product reads");
        }
        return authentication;
    }
}
