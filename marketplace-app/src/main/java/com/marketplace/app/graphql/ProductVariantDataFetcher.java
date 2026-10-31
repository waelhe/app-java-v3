package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.ProductVariant;
import com.marketplace.app.graphql.generated.types.ProductVariantCountableConnection;
import com.marketplace.app.graphql.generated.types.ProductVariantCountableEdge;
import com.marketplace.app.graphql.generated.types.ProductVariantFilterInput;
import com.marketplace.app.graphql.generated.types.ProductVariantSortingInput;
import com.marketplace.app.graphql.generated.types.ProductVariantSortField;
import com.marketplace.app.graphql.generated.types.ProductVariantWhereInput;
import com.marketplace.app.graphql.generated.types.OrderDirection;
import com.marketplace.catalog.spi.ProductVariantPage;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductVariantSort;
import com.marketplace.catalog.spi.ProductVariantView;
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
 * Saleor Products-domain {@code productVariant} and {@code productVariants}
 * read roots, packet campaign-b5-a — over the catalog owner's use cases
 * through the declared {@code ProductVariantReadSpi} seam. This class owns
 * ONLY transport mapping: global-ID decode/encode, argument validation,
 * cursor/position mapping and the connection envelope. Authorization
 * lives in the owning use case: these are Saleor PUBLIC storefront
 * roots whose include-unpublished variant maps to the platform's ADMIN
 * capability (the SaleorPermissionCatalog contract) — the public
 * population is the TWO-ROW gate (the variant's ACTIVE shelf AND the
 * parent product's ACTIVE shelf — the platform's own reservation-write
 * predicate as a read), the ADMIN caller's scroll/lookup widens to
 * every state and every parent (the registered permission-aware
 * population mapping divergence) — never in a UI route guard and never
 * duplicated here.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code productVariant} executes its {@code id} argument through the
 * typed global-ID codec (a non-ADMIN caller's lookup of a row outside
 * the public population answers the safe null — the two-row data-scope
 * boundary the owning use case enforces); {@code productVariants}
 * executes forward-only keyset paging ({@code first}/{@code after}) in
 * BOTH pinned sort fields — LAST_MODIFIED_AT (the entity's own
 * non-null {@code updated_at} audit column, deterministic with the
 * {@code id} tiebreaker) and ID (the unique key, the pinned enum's own
 * 3.23 value) — as REAL SQL keyset scrolling, and answers explicit
 * classified errors for the declared wire arguments whose owning
 * capability does not exist yet ({@code ids} — no owning id-set query
 * capability, {@code filter}, {@code where}, {@code search},
 * {@code channel} — no channel model, the PENDING-ADR
 * commercial-context row, and {@code before}/{@code last}, plus the
 * {@code productVariant} root's {@code sku} — the platform's SKU is
 * seller-namespaced, not globally unique, so a bare global SKU lookup
 * is ambiguous — {@code externalReference} — no external-ID column —
 * and {@code channel}) — recorded divergences, never silent no-ops and
 * never fake successes.</p>
 */
@DgsComponent
public class ProductVariantDataFetcher {

    /**
     * The default variant sort when the caller sends none — the pinned
     * enum's own ID value ascending (the unique key, deterministic on
     * its own — the stable default the storefront list rides).
     */
    private static final ProductVariantSort DEFAULT_SORT =
            ProductVariantSort.of(ProductVariantSort.Field.ID, true);

    private final ProductVariantReadSpi variantReadSpi;
    private final ProductVariantGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public ProductVariantDataFetcher(ProductVariantReadSpi variantReadSpi,
                                     ProductVariantGraphQlMapper mapper,
                                     GraphQlProperties properties,
                                     EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                     SaleorGlobalIdCodec globalIdCodec) {
        this.variantReadSpi = variantReadSpi;
        this.mapper = mapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code productVariants(...): ProductVariantCountableConnection}
     * — the store's variant population as a REAL SQL forward-only keyset
     * scroll (the staffUsers keyset precedent: cursors identify a row's
     * sort key, never an absolute offset) inside Saleor's exact
     * countable-connection envelope, including {@code totalCount} — the
     * CALLER-SCOPE population's own count (the two-row-gated public
     * population for non-ADMIN callers, every live row for the ADMIN
     * capability).
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): {@code ids} (no
     * owning id-set query capability), {@code filter} and {@code where}
     * (no owning query capability), {@code search} (no owning search
     * capability), {@code channel} (no channel model), and the backward
     * pagination. An empty or non-positive {@code first} answers the
     * empty page (the staffUsers field's own house contract), so
     * {@code totalCount} answers the honest not-computed null — never a
     * fabricated zero total.</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.ProductVariants)
    @RateLimiter(name = "graphqlProductVariantQuery")
    public ProductVariantCountableConnection productVariants(
            @InputArgument List<String> ids,
            @InputArgument String channel,
            @InputArgument ProductVariantFilterInput filter,
            @InputArgument ProductVariantWhereInput where,
            @InputArgument String search,
            @InputArgument ProductVariantSortingInput sortBy,
            @InputArgument String before,
            @InputArgument String after,
            @InputArgument Integer first,
            @InputArgument Integer last) {
        if (ids != null) {
            throw new IllegalArgumentException(
                    "productVariants ids filtering is not implemented on this platform yet");
        }
        if (filter != null) {
            throw new IllegalArgumentException(
                    "productVariants filter is not implemented on this platform yet");
        }
        if (where != null) {
            throw new IllegalArgumentException(
                    "productVariants where filter is not implemented on this platform yet");
        }
        if (search != null) {
            throw new IllegalArgumentException(
                    "productVariants search is not implemented on this platform yet");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "productVariants channel filtering is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "productVariants supports forward-only pagination (first/after) on this platform");
        }

        ProductVariantSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return ProductVariantCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = keysetPosition(after, sort);
        ProductVariantPage page = variantReadSpi.scrollVariants(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * Saleor {@code productVariant(id: ID, sku: String, externalReference:
     * String, channel: String): ProductVariant} — the storefront lookup
     * over the owning use case's permission-aware readable population.
     * The {@code id} argument executes through the typed global-ID
     * codec: an unknown but well-formed lookup answers null (the safe
     * absence); a KNOWN row outside the caller's population — a
     * non-ACTIVE variant, or an ACTIVE variant under a non-ACTIVE
     * parent product — answers null for non-ADMIN callers (the
     * two-row data-scope boundary the owning use case enforces); a
     * malformed or mistyped global ID answers the codec's classified
     * validation error — never a silent null and never a raw UUID
     * fallback. The {@code sku} (the platform's seller-namespaced SKU
     * is not globally unique — a bare global lookup is ambiguous),
     * {@code externalReference} (no external-ID column) and
     * {@code channel} (no channel model) arguments have no owning
     * capability on this platform and answer the explicit classified
     * error whenever they are sent; a call with no key at all answers
     * the classified validation error (a keyless lookup is a client
     * error here, never a fabricated absence — the recorded divergence
     * from upstream's null answer).
     */
    @DgsQuery(field = DgsConstants.QUERY.ProductVariant)
    @RateLimiter(name = "graphqlProductVariantQuery")
    public ProductVariant productVariant(@InputArgument String id,
                                         @InputArgument String sku,
                                         @InputArgument String externalReference,
                                         @InputArgument String channel) {
        Authentication authentication = currentAuthentication();
        if (sku != null) {
            throw new IllegalArgumentException(
                    "productVariant sku lookup is not implemented on this platform yet "
                            + "(the platform's SKU is seller-namespaced, not globally unique)");
        }
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "productVariant externalReference lookup is not implemented on this platform yet");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "productVariant channel filtering is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException(
                    "productVariant requires its id on this platform");
        }
        UUID nativeId = globalIdCodec.decode(id, ProductVariantGraphQlMapper.PRODUCT_VARIANT_TYPE);
        return variantReadSpi.findVariantById(nativeId, authentication)
                .map(mapper::toGraphQlProductVariant)
                .orElse(null);
    }

    /**
     * The sort mapping — Saleor's {@code ProductVariantSortingInput} onto
     * the owner's executed keyset sort. The pinned
     * {@code ProductVariantSortField} vocabulary closes over exactly two
     * values and BOTH have an owning keyset fact on this platform:
     * LAST_MODIFIED_AT (the entity's own non-null {@code updated_at}
     * audit column, {@code id} tiebreaker) and ID (the unique key) —
     * every declared sort value executes, in both directions.
     */
    private static ProductVariantSort toOwnerSort(ProductVariantSortingInput sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        boolean ascending = sortBy.getDirection() == null || OrderDirection.ASC.equals(sortBy.getDirection());
        ProductVariantSortField field = sortBy.getField() == null
                ? ProductVariantSortField.ID
                : sortBy.getField();
        return switch (field) {
            case ID -> ProductVariantSort.of(ProductVariantSort.Field.ID, ascending);
            case LAST_MODIFIED_AT -> ProductVariantSort.of(ProductVariantSort.Field.LAST_MODIFIED_AT, ascending);
        };
    }

    /**
     * The ONE shared cursor gate (the house KeysetCursorGate): the key set
     * is the executed sort's own (property, id) pair with the property's
     * declared value type — the ID sort's keyset is the unique key alone —
     * so a client-crafted cursor with well-named but unparseable values —
     * or a cursor emitted by the OTHER sort — answers the same VAL-001
     * taxonomy.
     */
    private KeysetScrollPosition keysetPosition(String after, ProductVariantSort sort) {
        if (sort.field() == ProductVariantSort.Field.LAST_MODIFIED_AT) {
            return KeysetCursorGate.forwardKeyset(cursorStrategy, after, "updatedAt",
                    KeysetCursorGate::instantValue, "productVariants with the same sortBy");
        }
        return KeysetCursorGate.forwardKeyset(cursorStrategy, after, "id",
                KeysetCursorGate::uuidValue, "productVariants with the same sortBy");
    }

    /**
     * The connection envelope — the declared (not runtime-generated)
     * Saleor shape, built from the Window's own per-element scroll
     * positions (the exact positions the framework's connection
     * adaptation emits for its generated connections — and the exact
     * positions the public population's hand-built windows carry: ONE
     * cursor strategy, one cursor semantics across both paths).
     * {@code hasPreviousPage} is the forward-only honest fact: a scroll
     * that started after a known element has elements before it.
     * {@code totalCount} is the caller-scope population's count (GraphQL
     * Int's signed 32-bit ceiling documented, hence the clamp).
     */
    private ProductVariantCountableConnection toConnection(ProductVariantPage page,
                                                           KeysetScrollPosition position) {
        Window<ProductVariantView> window = page.window();
        List<ProductVariantView> content = window.getContent();
        List<ProductVariantCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(ProductVariantCountableEdge.newBuilder()
                    .node(mapper.toGraphQlProductVariant(content.get(i)))
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
        return ProductVariantCountableConnection.newBuilder()
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
                    "Authentication is required for product-variant reads");
        }
        return authentication;
    }
}
