package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.OrderDirection;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.ProductTypeCountableConnection;
import com.marketplace.app.graphql.generated.types.ProductTypeCountableEdge;
import com.marketplace.app.graphql.generated.types.ProductType;
import com.marketplace.app.graphql.generated.types.ProductTypeFilterInput;
import com.marketplace.app.graphql.generated.types.ProductTypeSortField;
import com.marketplace.app.graphql.generated.types.ProductTypeSortingInput;
import com.marketplace.catalog.spi.ProductTypePage;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductTypeSort;
import com.marketplace.catalog.spi.ProductTypeView;
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
 * Saleor Products-domain product-type read roots — {@code productType} and
 * {@code productTypes} — over the catalog owner's use cases through the
 * declared {@code ProductTypeReadSpi} seam. This class owns ONLY transport
 * mapping: global-ID decode/encode, argument validation, cursor/position
 * mapping and the connection envelope. Authorization lives in the owning
 * use case (the ADMIN-enforced {@code ProductTypeReadService} — the
 * platform's server-side mapping of Saleor's staff MANAGE_PRODUCTS-class
 * requirement), never in a UI route guard and never duplicated here.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code productType} executes its {@code id} argument through the typed
 * global-ID codec; {@code productTypes} executes forward-only keyset
 * paging ({@code first}/{@code after}) in the one sort with an owning
 * fact (NAME — deterministic with the {@code id} tiebreaker; DIGITAL and
 * SHIPPING_REQUIRED answer the explicit classified not-implemented error)
 * and answers explicit classified errors for the declared wire arguments
 * whose owning capability does not exist yet ({@code filter},
 * {@code before}/{@code last}) — recorded divergences, never silent
 * no-ops and never fake successes.</p>
 */
@DgsComponent
public class ProductTypeDataFetcher {

    /**
     * The default product-type sort when the caller sends none — the
     * rendered display-name ordering ascending (Saleor's own ProductType
     * model ordering by name), deterministic with the {@code id}
     * tiebreaker.
     */
    private static final ProductTypeSort DEFAULT_SORT =
            ProductTypeSort.of(ProductTypeSort.Field.NAME, true);

    private final ProductTypeReadSpi productTypeReadSpi;
    private final ProductTypeGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public ProductTypeDataFetcher(ProductTypeReadSpi productTypeReadSpi,
                                  ProductTypeGraphQlMapper mapper,
                                  GraphQlProperties properties,
                                  EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                  SaleorGlobalIdCodec globalIdCodec) {
        this.productTypeReadSpi = productTypeReadSpi;
        this.mapper = mapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code productTypes(...): ProductTypeCountableConnection} —
     * the product-type reference collection as a forward-only keyset scroll
     * (the staffUsers/permissionGroups keyset precedent: cursors identify a
     * row's sort key, never an absolute offset) inside Saleor's exact
     * countable-connection envelope, including {@code totalCount}.
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): the boundary is
     * tested, recorded in the divergence registry, and never a silent
     * no-op. An empty or non-positive {@code first} answers the empty page
     * (the staffUsers field's own house contract), so {@code totalCount}
     * answers the honest not-computed null — never a fabricated zero
     * total.</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.ProductTypes)
    @RateLimiter(name = "graphqlCatalogRead")
    public ProductTypeCountableConnection productTypes(
            @InputArgument Integer first,
            @InputArgument String after,
            @InputArgument String before,
            @InputArgument Integer last,
            @InputArgument ProductTypeSortingInput sortBy,
            @InputArgument ProductTypeFilterInput filter) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "productTypes filter is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "productTypes supports forward-only pagination (first/after) on this platform");
        }

        ProductTypeSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return ProductTypeCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = keysetPosition(after);
        ProductTypePage page = productTypeReadSpi.scrollProductTypes(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * Saleor {@code productType(id: ID!): ProductType} — the staff lookup,
     * ADMIN-enforced in the catalog use case. The {@code id} argument
     * executes through the typed global-ID codec: an unknown but
     * well-formed lookup answers null (the safe absence); a malformed or
     * mistyped global ID answers the codec's classified validation error —
     * never a silent null and never a raw UUID fallback.
     */
    @DgsQuery(field = DgsConstants.QUERY.ProductType)
    @RateLimiter(name = "graphqlCatalogRead")
    public ProductType productType(@InputArgument String id) {
        Authentication authentication = currentAuthentication();
        UUID nativeId = globalIdCodec.decode(id, ProductTypeGraphQlMapper.PRODUCT_TYPE_TYPE);
        return productTypeReadSpi.findProductTypeById(nativeId, authentication)
                .map(mapper::toGraphQlProductType)
                .orElse(null);
    }

    /**
     * The sort mapping — Saleor's {@code ProductTypeSortingInput} onto the
     * owner's executed keyset sort. NAME has an owning fact (the rendered
     * display name) and executes; DIGITAL (deprecated upstream) and
     * SHIPPING_REQUIRED have no owning column on this platform and answer
     * the explicit not-supported error — the staffUsers boundary's own
     * classified-error contract, never a silent fallback to another sort.
     */
    private static ProductTypeSort toOwnerSort(ProductTypeSortingInput sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        boolean ascending = sortBy.getDirection() == null || OrderDirection.ASC.equals(sortBy.getDirection());
        ProductTypeSortField field = sortBy.getField() == null
                ? ProductTypeSortField.NAME
                : sortBy.getField();
        return switch (field) {
            case NAME -> ProductTypeSort.of(ProductTypeSort.Field.NAME, ascending);
            case DIGITAL, SHIPPING_REQUIRED -> throw new IllegalArgumentException(
                    "productTypes sort field " + field + " is not implemented on this platform yet");
        };
    }

    /**
     * The ONE shared cursor gate (the house KeysetCursorGate): the key set
     * is the sort's own (property, id) pair with the property's declared
     * value type, so a client-crafted cursor with well-named but
     * unparseable values answers the same VAL-001 taxonomy.
     */
    private KeysetScrollPosition keysetPosition(String after) {
        return KeysetCursorGate.forwardKeyset(cursorStrategy, after, "name", KeysetCursorGate::textValue,
                "productTypes with the same sortBy");
    }

    /**
     * The connection envelope — the declared (not runtime-generated)
     * Saleor shape, built from the Window's own per-element scroll
     * positions (the exact positions the framework's connection adaptation
     * emits for its generated connections — one cursor strategy
     * (toCursor/fromCursor), one cursor semantics across the whole
     * surface). {@code hasPreviousPage} is the forward-only honest fact: a
     * scroll that started after a known element has elements before it.
     * {@code totalCount} is the collection count (GraphQL Int's signed
     * 32-bit ceiling documented, hence the clamp).
     */
    private ProductTypeCountableConnection toConnection(ProductTypePage page, KeysetScrollPosition position) {
        Window<ProductTypeView> window = page.window();
        List<ProductTypeView> content = window.getContent();
        List<ProductTypeCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(ProductTypeCountableEdge.newBuilder()
                    .node(mapper.toGraphQlProductType(content.get(i)))
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
        return ProductTypeCountableConnection.newBuilder()
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
                    "Authentication is required for product-type administration");
        }
        return authentication;
    }
}
