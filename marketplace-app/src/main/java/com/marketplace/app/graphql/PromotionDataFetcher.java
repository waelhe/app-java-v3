package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.OrderDirection;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.Promotion;
import com.marketplace.app.graphql.generated.types.PromotionCountableConnection;
import com.marketplace.app.graphql.generated.types.PromotionCountableEdge;
import com.marketplace.app.graphql.generated.types.PromotionSortField;
import com.marketplace.app.graphql.generated.types.PromotionSortingInput;
import com.marketplace.app.graphql.generated.types.PromotionWhereInput;
import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.pricing.spi.PromotionPage;
import com.marketplace.pricing.spi.PromotionSort;
import com.marketplace.pricing.spi.PromotionView;
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
 * P3 (the canonical Saleor plan §5-P3): the GraphQL adapter for the
 * Saleor Discounts-domain promotion read roots — {@code promotion} and
 * {@code promotions} — over the pricing owner's use cases through the
 * declared {@code DiscountReadSpi} seam. This class owns ONLY transport
 * mapping: global-ID decode/encode, argument validation, cursor/position
 * mapping and the connection envelope. Authorization lives in the owning
 * use case (the ADMIN-enforced {@code DiscountReadService} — the
 * platform's server-side mapping of Saleor's staff MANAGE_DISCOUNTS
 * requirement; the pinned schema's own annotation on both promotion
 * roots — the campaign ledger's MANAGE_PRODUCTS row for
 * {@code promotions} is a transcription error reported to A0), never in
 * a UI route guard and never duplicated here.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code promotion} executes its {@code id} argument through the typed
 * global-ID codec, while its {@code externalReference} argument (no
 * external-ID column exists on this platform) answers the explicit
 * classified not-implemented error — never a silent no-op. {@code
 * promotions} executes forward-only keyset paging ({@code first}/
 * {@code after}) in the sorts with owning non-null keyset columns
 * (NAME and CREATED_AT, each deterministic with the {@code id}
 * tiebreaker; START_DATE/END_DATE — the optional window columns that
 * cannot carry deterministic keyset cursors — answer the explicit
 * classified error) and answers explicit classified errors for the
 * declared wire arguments whose owning capability does not exist yet
 * ({@code where}, {@code before}/{@code last}) — recorded divergences,
 * never silent no-ops and never fake successes.</p>
 */
@DgsComponent
public class PromotionDataFetcher {

    /**
     * The default promotion sort when the caller sends none — the policy
     * name ordering ascending (Saleor's own Promotion model ordering by
     * name), deterministic with the {@code id} tiebreaker.
     */
    private static final PromotionSort DEFAULT_SORT =
            PromotionSort.of(PromotionSort.Field.NAME, true);

    private final DiscountReadSpi discountReadSpi;
    private final PromotionGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public PromotionDataFetcher(DiscountReadSpi discountReadSpi,
                                PromotionGraphQlMapper mapper,
                                GraphQlProperties properties,
                                EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                SaleorGlobalIdCodec globalIdCodec) {
        this.discountReadSpi = discountReadSpi;
        this.mapper = mapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code promotions(...): PromotionCountableConnection} — the
     * promotion policy collection as a forward-only keyset scroll (the
     * staffUsers/permissionGroups/productTypes keyset precedent: cursors
     * identify a row's sort key, never an absolute offset) inside
     * Saleor's exact countable-connection envelope, including {@code
     * totalCount}.
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): the boundary is
     * tested, recorded in the divergence registry, and never a silent
     * no-op. An empty or non-positive {@code first} answers the empty
     * page (the staffUsers field's own house contract), so {@code
     * totalCount} answers the honest not-computed null — never a
     * fabricated zero total.</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.Promotions)
    @RateLimiter(name = "graphqlPromotionQuery")
    public PromotionCountableConnection promotions(
            @InputArgument PromotionWhereInput where,
            @InputArgument PromotionSortingInput sortBy,
            @InputArgument String before,
            @InputArgument String after,
            @InputArgument Integer first,
            @InputArgument Integer last) {
        if (where != null) {
            throw new IllegalArgumentException(
                    "promotions where is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "promotions supports forward-only pagination (first/after) on this platform");
        }

        PromotionSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            // The degenerate page: no query executes (the staffUsers field's
            // own house contract), so totalCount answers the honest
            // not-computed null — never a fabricated zero total.
            return PromotionCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = keysetPosition(after, sort);
        PromotionPage page = discountReadSpi.scrollPromotions(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * Saleor {@code promotion(id: ID, externalReference: String): Promotion}
     * — the staff lookup, ADMIN-enforced in the pricing use case (Saleor
     * requires MANAGE_DISCOUNTS — the mapped platform capability). The
     * {@code id} argument executes through the typed global-ID codec: an
     * unknown but well-formed lookup answers null (the safe absence); a
     * malformed or mistyped global ID answers the codec's classified
     * validation error — never a silent null and never a raw UUID
     * fallback. The {@code externalReference} argument has no owning
     * column on this platform and answers the explicit classified
     * not-implemented error — never a silent no-op.
     */
    @DgsQuery(field = DgsConstants.QUERY.Promotion)
    @RateLimiter(name = "graphqlPromotionQuery")
    public Promotion promotion(@InputArgument String id,
                               @InputArgument String externalReference) {
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "promotion externalReference is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException(
                    "promotion requires an id on this platform (externalReference is not implemented yet)");
        }

        Authentication authentication = currentAuthentication();
        UUID nativeId = globalIdCodec.decode(id, PromotionGraphQlMapper.PROMOTION_TYPE);
        return discountReadSpi.findPromotionById(nativeId, authentication)
                .map(mapper::toGraphQlPromotion)
                .orElse(null);
    }

    /**
     * The sort mapping — Saleor's {@code PromotionSortingInput} onto the
     * owner's executed keyset sort. NAME and CREATED_AT have owning
     * non-null columns and execute; START_DATE/END_DATE (the optional
     * policy-window columns) cannot carry deterministic keyset cursors
     * under the house map-based cursor contract and answer the explicit
     * classified not-implemented error — never a silent fallback to
     * another sort.
     */
    private static PromotionSort toOwnerSort(PromotionSortingInput sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        boolean ascending = sortBy.getDirection() == null || OrderDirection.ASC.equals(sortBy.getDirection());
        PromotionSortField field = sortBy.getField() == null
                ? PromotionSortField.NAME
                : sortBy.getField();
        return switch (field) {
            case NAME -> PromotionSort.of(PromotionSort.Field.NAME, ascending);
            case CREATED_AT -> PromotionSort.of(PromotionSort.Field.CREATED_AT, ascending);
            case START_DATE, END_DATE -> throw new IllegalArgumentException(
                    "promotions sort field " + field + " is not implemented on this platform yet");
        };
    }

    /**
     * The ONE shared cursor gate (the house KeysetCursorGate): the key set
     * is the sort's own (property, id) pair with the property's declared
     * value type, so a client-crafted cursor with well-named but
     * unparseable values answers the same VAL-001 taxonomy.
     */
    private KeysetScrollPosition keysetPosition(String after, PromotionSort sort) {
        return sort.field() == PromotionSort.Field.NAME
                ? KeysetCursorGate.forwardKeyset(cursorStrategy, after, "name",
                        KeysetCursorGate::textValue, "promotions with the same sortBy")
                : KeysetCursorGate.forwardKeyset(cursorStrategy, after, "createdAt",
                        KeysetCursorGate::instantValue, "promotions with the same sortBy");
    }

    /**
     * The connection envelope — the declared (not runtime-generated)
     * Saleor shape, built from the Window's own per-element scroll
     * positions (the exact positions the framework's connection
     * adaptation emits for its generated connections — one cursor
     * strategy (toCursor/fromCursor), one cursor semantics across the
     * whole surface). {@code hasPreviousPage} is the forward-only honest
     * fact: a scroll that started after a known element has elements
     * before it. {@code totalCount} is the collection count (GraphQL
     * Int's signed 32-bit ceiling documented, hence the clamp).
     */
    private PromotionCountableConnection toConnection(PromotionPage page, KeysetScrollPosition position) {
        Window<PromotionView> window = page.window();
        List<PromotionView> content = window.getContent();
        List<PromotionCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(PromotionCountableEdge.newBuilder()
                    .node(mapper.toGraphQlPromotion(content.get(i)))
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
        return PromotionCountableConnection.newBuilder()
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
                    "Authentication is required for promotion administration");
        }
        return authentication;
    }
}
