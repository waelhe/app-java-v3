package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.CollectionCountableConnection;
import com.marketplace.app.graphql.generated.types.CollectionCountableEdge;
import com.marketplace.app.graphql.generated.types.CollectionFilterInput;
import com.marketplace.app.graphql.generated.types.CollectionSortField;
import com.marketplace.app.graphql.generated.types.CollectionSortingInput;
import com.marketplace.app.graphql.generated.types.CollectionWhereInput;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.ProductCountableConnection;
import com.marketplace.app.graphql.generated.types.ProductCountableEdge;
import com.marketplace.app.graphql.generated.types.ProductFilterInput;
import com.marketplace.app.graphql.generated.types.ProductOrder;
import com.marketplace.app.graphql.generated.types.ProductWhereInput;
import com.marketplace.catalog.spi.CollectionPage;
import com.marketplace.catalog.spi.CollectionProductsPage;
import com.marketplace.catalog.spi.CollectionReadSpi;
import com.marketplace.catalog.spi.CollectionSort;
import com.marketplace.catalog.spi.CollectionView;
import com.marketplace.catalog.spi.ProductView;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
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
import java.util.Map;
import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Products-domain {@code collection} and {@code collections} read
 * roots and the {@code Collection.products} field, packet agent-2 — over
 * the catalog owner's use cases through the declared
 * {@code CollectionReadSpi} seam. This class owns ONLY transport mapping:
 * global-ID decode/encode, argument validation, cursor/position mapping
 * and the connection envelopes. Authorization lives in the owning use
 * case — these are Saleor PUBLIC storefront roots for the published
 * content, and the include-unpublished surface (the pinned
 * MANAGE_ORDERS/MANAGE_DISCOUNTS/MANAGE_PRODUCTS permission family)
 * maps to the platform's ADMIN capability over the received
 * {@code Authentication} (the frozen authorization contract): the
 * platform's authentication boundary for this surface is the
 * {@code /graphql} chain itself, never a UI route guard and never
 * duplicated here.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code collection} executes its {@code id} argument through the typed
 * global-ID codec and its {@code slug} argument through the owner's
 * slug lookup (upstream's id-first precedence between the two EXECUTABLE
 * keys); {@code collections} executes forward-only keyset paging
 * ({@code first}/{@code after}) in the one sort with an owning fact
 * (NAME — deterministic with the {@code id} tiebreaker) and answers
 * explicit classified errors for the declared wire arguments whose
 * owning capability does not exist yet ({@code filter}, {@code where},
 * the unowned {@code sortBy} field PRODUCT_COUNT, {@code channel} (no
 * commerce-channel model), and {@code before}/{@code last}), plus the
 * {@code collection} root's {@code slugLanguageCode} (no
 * slug-translation owner), {@code externalReference} (no external-ID
 * column), {@code channel} and its keyless call shape) — recorded
 * divergences, never silent no-ops and never fake successes. The
 * {@code Collection.products} field rides the membership's own
 * (position, id) ordering key with the same forward-only law and the
 * same classified-error boundary over its own declared-but-unowned
 * arguments.</p>
 */
@DgsComponent
public class CollectionDataFetcher {

    /**
     * The default collection sort when the caller sends none — the
     * rendered display-name ordering ascending (the category slice's own
     * default), deterministic with the {@code id} tiebreaker.
     */
    private static final CollectionSort DEFAULT_SORT =
            CollectionSort.of(CollectionSort.Field.NAME, true);

    private final CollectionReadSpi collectionReadSpi;
    private final CollectionGraphQlMapper collectionMapper;
    private final ProductGraphQlMapper productMapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public CollectionDataFetcher(CollectionReadSpi collectionReadSpi,
                                 CollectionGraphQlMapper collectionMapper,
                                 ProductGraphQlMapper productMapper,
                                 GraphQlProperties properties,
                                 EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                 SaleorGlobalIdCodec globalIdCodec) {
        this.collectionReadSpi = collectionReadSpi;
        this.collectionMapper = collectionMapper;
        this.productMapper = productMapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code collections(...): CollectionCountableConnection} — the
     * storefront collection registry as a forward-only keyset scroll (the
     * categories keyset precedent: cursors identify a row's sort key,
     * never an absolute offset) inside Saleor's exact countable-connection
     * envelope, including {@code totalCount} (the caller's own visibility
     * — the publication law governs the count the same way it governs the
     * rows).
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): {@code filter} and
     * {@code where} (no owning query capability), the unowned sort field,
     * {@code channel} (no commerce-channel model), and the backward
     * pagination. An empty or non-positive {@code first} answers the empty
     * page (the staffUsers field's own house contract).</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.Collections)
    @RateLimiter(name = "graphqlCollectionQuery")
    public CollectionCountableConnection collections(
            @InputArgument Integer first,
            @InputArgument String after,
            @InputArgument String before,
            @InputArgument Integer last,
            @InputArgument CollectionSortingInput sortBy,
            @InputArgument CollectionFilterInput filter,
            @InputArgument CollectionWhereInput where,
            @InputArgument String channel) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "collections filter is not implemented on this platform yet");
        }
        if (where != null) {
            throw new IllegalArgumentException(
                    "collections where filter is not implemented on this platform yet");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "collections channel is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "collections supports forward-only pagination (first/after) on this platform");
        }

        CollectionSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return CollectionCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = KeysetCursorGate.forwardKeyset(
                cursorStrategy, after, sort.property(),
                KeysetCursorGate::textValue, "collections with the same sortBy");
        CollectionPage page = collectionReadSpi.scrollCollections(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * Saleor {@code collection(id: ID, slug: String, slugLanguageCode:
     * LanguageCodeEnum, externalReference: String, channel: String):
     * Collection} — the public storefront lookup over the registry. The
     * {@code id} argument executes through the typed global-ID codec: an
     * unknown but well-formed lookup (or an unpublished row for a
     * non-ADMIN caller) answers null (the safe absence); a malformed or
     * mistyped global ID answers the codec's classified validation error —
     * never a silent null and never a raw UUID fallback. The {@code slug}
     * argument executes through the owner's slug lookup: an unknown but
     * well-formed slug likewise answers null. When both executable keys
     * are sent, {@code id} wins — upstream's own documented precedence.
     * The {@code slugLanguageCode}, {@code externalReference} and
     * {@code channel} arguments have no owning capability on this
     * platform and answer the explicit classified error whenever they are
     * sent; a call with no key at all answers the classified validation
     * error.
     */
    @DgsQuery(field = DgsConstants.QUERY.Collection)
    @RateLimiter(name = "graphqlCollectionQuery")
    public com.marketplace.app.graphql.generated.types.Collection collection(
            @InputArgument String id,
            @InputArgument String slug,
            @InputArgument String slugLanguageCode,
            @InputArgument String externalReference,
            @InputArgument String channel) {
        Authentication authentication = currentAuthentication();
        if (slugLanguageCode != null) {
            throw new IllegalArgumentException(
                    "collection slugLanguageCode lookup is not implemented on this platform yet");
        }
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "collection externalReference lookup is not implemented on this platform yet");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "collection channel is not implemented on this platform yet");
        }
        if (id == null && slug == null) {
            throw new IllegalArgumentException(
                    "collection requires one of id or slug on this platform");
        }
        if (id != null) {
            UUID nativeId = globalIdCodec.decode(id, CollectionGraphQlMapper.COLLECTION_TYPE);
            return collectionReadSpi.findCollectionById(nativeId, authentication)
                    .map(collectionMapper::toGraphQlCollection)
                    .orElse(null);
        }
        return collectionReadSpi.findCollectionBySlug(slug, authentication)
                .map(collectionMapper::toGraphQlCollection)
                .orElse(null);
    }

    /**
     * Saleor {@code Collection.products(...): ProductCountableConnection} —
     * one collection's member products over the membership registry's own
     * (position, id) ordering key (the display order's own law), inside
     * Saleor's exact countable-connection envelope. The member-product
     * publication law governs the content and the total alike (the
     * frozen "unpublished product never readable without delegation"
     * contract).
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors: {@code filter}/{@code where} (no owning query
     * capability), {@code search} (no search owner on this field),
     * {@code sortBy} (the display order IS the field's own law — no
     * alternative sort exists), {@code channel} (no commerce-channel
     * model), and the backward pagination.</p>
     */
    @DgsData(parentType = DgsConstants.COLLECTION.TYPE_NAME, field = DgsConstants.COLLECTION.Products)
    @RateLimiter(name = "graphqlCollectionQuery")
    public ProductCountableConnection products(
            com.netflix.graphql.dgs.DgsDataFetchingEnvironment dataFetchingEnvironment,
            @InputArgument ProductFilterInput filter,
            @InputArgument ProductWhereInput where,
            @InputArgument String search,
            @InputArgument ProductOrder sortBy,
            @InputArgument String before,
            @InputArgument String after,
            @InputArgument Integer first,
            @InputArgument Integer last,
            @InputArgument String channel) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "Collection products filter is not implemented on this platform yet");
        }
        if (where != null) {
            throw new IllegalArgumentException(
                    "Collection products where filter is not implemented on this platform yet");
        }
        if (search != null) {
            throw new IllegalArgumentException(
                    "Collection products search is not implemented on this platform yet");
        }
        if (sortBy != null) {
            throw new IllegalArgumentException(
                    "Collection products sortBy is not implemented on this platform yet "
                            + "(the collection's own display order is the field's ordering law)");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "Collection products channel is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "Collection products supports forward-only pagination (first/after) on this platform");
        }

        // The parent wire object's typed global ID — the collection's own
        // native key behind the codec (a malformed or mistyped ID answers
        // the codec's classified validation error, never a silent null).
        com.marketplace.app.graphql.generated.types.Collection parent =
                dataFetchingEnvironment.getSource();
        UUID collectionId = globalIdCodec.decode(parent.getId(),
                CollectionGraphQlMapper.COLLECTION_TYPE);

        Authentication authentication = currentAuthentication();
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
        // The membership's own ordering key is int-typed (the position
        // column's own law — the normalizer hands the gate the declared
        // type, the same discipline every owning sort carries).
        KeysetScrollPosition position = KeysetCursorGate.forwardKeyset(
                cursorStrategy, after, "position",
                value -> value instanceof Number number ? number.intValue()
                        : Integer.parseInt(String.valueOf(value)),
                "collection products with the same display order");
        CollectionProductsPage page = collectionReadSpi.scrollCollectionProducts(
                collectionId, position, Limit.of(pageSize), authentication);
        return toProductsConnection(page, position);
    }

    /**
     * The sort mapping — Saleor's {@code CollectionSortingInput} onto the
     * owner's executed keyset sort. NAME (the rendered display name) has
     * an owning fact and executes; PRODUCT_COUNT (a products-connection
     * count the scroll does not own) answers the explicit not-supported
     * error — never a silent fallback to another sort.
     */
    private static CollectionSort toOwnerSort(CollectionSortingInput sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        boolean ascending = sortBy.getDirection() == null
                || com.marketplace.app.graphql.generated.types.OrderDirection.ASC
                        .equals(sortBy.getDirection());
        CollectionSortField field = sortBy.getField() == null
                ? CollectionSortField.NAME
                : sortBy.getField();
        return switch (field) {
            case NAME -> CollectionSort.of(CollectionSort.Field.NAME, ascending);
            case PRODUCT_COUNT -> throw new IllegalArgumentException(
                    "collections sort field " + field + " is not implemented on this platform yet");
        };
    }

    /**
     * The collection connection envelope — the declared (not
     * runtime-generated) Saleor shape, built from the Window's own
     * per-element scroll positions. {@code hasPreviousPage} is the
     * forward-only honest fact; {@code totalCount} is the caller's own
     * visible registry count (GraphQL Int's signed 32-bit ceiling
     * documented, hence the clamp).
     */
    private CollectionCountableConnection toConnection(CollectionPage page,
                                                       KeysetScrollPosition position) {
        Window<CollectionView> window = page.window();
        List<CollectionView> content = window.getContent();
        List<CollectionCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(CollectionCountableEdge.newBuilder()
                    .node(collectionMapper.toGraphQlCollection(content.get(i)))
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
        return CollectionCountableConnection.newBuilder()
                .edges(edges)
                .pageInfo(pageInfo)
                .totalCount((int) Math.min(page.total(), Integer.MAX_VALUE))
                .build();
    }

    /**
     * The member-products connection envelope — the same declared Saleor
     * shape over the membership's own ordering key.
     */
    private ProductCountableConnection toProductsConnection(CollectionProductsPage page,
                                                            KeysetScrollPosition position) {
        Window<ProductView> window = page.window();
        List<ProductView> content = window.getContent();
        List<ProductCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(ProductCountableEdge.newBuilder()
                    .node(productMapper.toGraphQlProduct(content.get(i)))
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
                    "Authentication is required for collection reads");
        }
        return authentication;
    }
}
