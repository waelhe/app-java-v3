package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.Order;
import com.marketplace.app.graphql.generated.types.OrderCountableConnection;
import com.marketplace.app.graphql.generated.types.OrderCountableEdge;
import com.marketplace.app.graphql.generated.types.OrderFilterInput;
import com.marketplace.app.graphql.generated.types.OrderSortingInput;
import com.marketplace.app.graphql.generated.types.OrderSortField;
import com.marketplace.app.graphql.generated.types.OrderWhereInput;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.OrderDirection;
import com.marketplace.orders.spi.OrderPage;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.orders.spi.OrderSort;
import com.marketplace.orders.spi.OrderView;
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
 * P4 (the canonical Saleor plan §5-P4): the GraphQL adapter for the
 * Saleor Orders-domain {@code order} and {@code orders} read roots,
 * packet campaign-b5-b — over the orders owner's use cases through the
 * declared {@code OrderReadSpi} seam. This class owns ONLY transport
 * mapping: global-ID decode/encode, argument validation, cursor/position
 * mapping and the connection envelope. Authorization lives in the
 * owning use case: these are Saleor MANAGEMENT roots — "Requires one of
 * the following permissions: MANAGE_ORDERS" — mapped through the
 * platform's ADMIN capability (the SaleorPermissionCatalog contract)
 * and enforced at the owning use-case boundary by Spring Security's
 * method security (the same mechanism the identity module's
 * staff-user reads established), never in a UI route guard and never
 * duplicated here. There is no public population on this root: a
 * non-ADMIN caller answers the authorization failure (AUTHZ-001 by
 * the resolver's taxonomy), and the buyer's own-order surface is the
 * User.orders field's own later slice.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code orders} executes forward-only keyset paging
 * ({@code first}/{@code after}) in the THREE sorts with an owning
 * keyset fact — CREATED_AT (the placement instant, the D-N5 complete
 * ordering key's own column, deterministic with the {@code id}
 * tiebreaker; the deprecated CREATION_DATE alias rides the SAME
 * executed sort), LAST_MODIFIED_AT (the entity's own audit column)
 * and STATUS (the stored lifecycle text) — as REAL SQL keyset
 * scrolling, and answers explicit classified errors for the declared
 * wire arguments whose owning capability does not exist yet
 * ({@code filter}, {@code where}, {@code search}, {@code channel} —
 * no channel model, the PENDING-ADR commercial-context row —
 * {@code before}/{@code last}, and the five unowned sort fields:
 * NUMBER — no order-number column, RANK — search-scoped upstream,
 * CUSTOMER — an identity-side join this surface does not own, PAYMENT
 * and FULFILLMENT_STATUS — deprecated upstream); {@code order}
 * executes its {@code id} argument through the typed global-ID codec
 * and answers the explicit classified error for
 * {@code externalReference} (no external-ID column) and the keyless
 * call — recorded divergences, never silent no-ops and never fake
 * successes.</p>
 */
@DgsComponent
public class OrderDataFetcher {

    /**
     * The default order sort when the caller sends none — the placement
     * instant DESCENDING with the {@code id} tiebreaker: the newest
     * order first, the D-N5 complete ordering key's own direction (the
     * buyer/seller histories' own house default, deterministic).
     */
    private static final OrderSort DEFAULT_SORT =
            OrderSort.of(OrderSort.Field.CREATED_AT, false);

    private final OrderReadSpi orderReadSpi;
    private final OrderGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public OrderDataFetcher(OrderReadSpi orderReadSpi,
                            OrderGraphQlMapper mapper,
                            GraphQlProperties properties,
                            EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                            SaleorGlobalIdCodec globalIdCodec) {
        this.orderReadSpi = orderReadSpi;
        this.mapper = mapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code orders(...): OrderCountableConnection} — the store's
     * order population as a REAL SQL forward-only keyset scroll (the
     * staffUsers keyset precedent: cursors identify a row's sort key,
     * never an absolute offset) inside Saleor's exact countable
     * connection envelope, including {@code totalCount} — the whole
     * live order population's own count (the ADMIN-only management
     * surface; the owning use case denies every non-ADMIN caller with
     * the authorization failure before any row is read).
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): the deprecated
     * {@code filter}, {@code where}, {@code search}, {@code channel} and
     * the backward pagination. An empty or non-positive {@code first}
     * answers the empty page (the staffUsers field's own house
     * contract), so {@code totalCount} answers the honest not-computed
     * null — never a fabricated zero total.</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.Orders)
    @RateLimiter(name = "graphqlOrderQuery")
    public OrderCountableConnection orders(
            @InputArgument OrderSortingInput sortBy,
            @InputArgument OrderFilterInput filter,
            @InputArgument OrderWhereInput where,
            @InputArgument String channel,
            @InputArgument String search,
            @InputArgument String before,
            @InputArgument String after,
            @InputArgument Integer first,
            @InputArgument Integer last) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "orders filter is not implemented on this platform yet");
        }
        if (where != null) {
            throw new IllegalArgumentException(
                    "orders where filter is not implemented on this platform yet");
        }
        if (search != null) {
            throw new IllegalArgumentException(
                    "orders search is not implemented on this platform yet");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "orders channel filtering is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "orders supports forward-only pagination (first/after) on this platform");
        }

        OrderSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return OrderCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = keysetPosition(after, sort);
        OrderPage page = orderReadSpi.scrollOrders(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * Saleor {@code order(id: ID, externalReference: String): Order} —
     * the management lookup over the owning use case's ADMIN-enforced
     * read. The {@code id} argument executes through the typed
     * global-ID codec: an unknown but well-formed lookup answers null
     * (the safe absence); a malformed or mistyped global ID answers the
     * codec's classified validation error — never a silent null and
     * never a raw UUID fallback. The {@code externalReference} argument
     * (no external-ID column on this platform) answers the explicit
     * classified error whenever it is sent; a call with no key at all
     * answers the classified validation error (a keyless lookup is a
     * client error here, never a fabricated absence — the recorded
     * divergence from upstream's null answer).
     */
    @DgsQuery(field = DgsConstants.QUERY.Order)
    @RateLimiter(name = "graphqlOrderQuery")
    public Order order(@InputArgument String id,
                       @InputArgument String externalReference) {
        Authentication authentication = currentAuthentication();
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "order externalReference lookup is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException(
                    "order requires its id on this platform");
        }
        UUID nativeId = globalIdCodec.decode(id, OrderGraphQlMapper.ORDER_TYPE);
        return orderReadSpi.findOrderById(nativeId, authentication)
                .map(mapper::toGraphQlOrder)
                .orElse(null);
    }

    /**
     * The sort mapping — Saleor's {@code OrderSortingInput} onto the
     * owner's executed keyset sort. THREE fields have an owning keyset
     * fact and execute (CREATED_AT — with the deprecated CREATION_DATE
     * alias riding the SAME executed sort, exactly upstream's own
     * deprecation mapping; LAST_MODIFIED_AT; STATUS); the five unowned
     * fields (NUMBER — no order-number column, RANK — search-scoped,
     * CUSTOMER — an identity-side join, PAYMENT/FULFILLMENT_STATUS —
     * deprecated upstream) answer the explicit not-supported error —
     * the staffUsers boundary's own classified-error contract, never a
     * silent fallback to another sort.
     */
    private static OrderSort toOwnerSort(OrderSortingInput sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        boolean ascending = sortBy.getDirection() == null || OrderDirection.ASC.equals(sortBy.getDirection());
        OrderSortField field = sortBy.getField() == null
                ? OrderSortField.CREATED_AT
                : sortBy.getField();
        return switch (field) {
            case CREATED_AT, CREATION_DATE -> OrderSort.of(OrderSort.Field.CREATED_AT, ascending);
            case LAST_MODIFIED_AT -> OrderSort.of(OrderSort.Field.LAST_MODIFIED_AT, ascending);
            case STATUS -> OrderSort.of(OrderSort.Field.STATUS, ascending);
            case NUMBER, RANK, CUSTOMER, PAYMENT, FULFILLMENT_STATUS -> throw new IllegalArgumentException(
                    "orders sort field " + field + " is not implemented on this platform yet");
        };
    }

    /**
     * The ONE shared cursor gate (the house KeysetCursorGate): the key set
     * is the executed sort's own (property, id) pair with the property's
     * declared value type, so a client-crafted cursor with well-named but
     * unparseable values — or a cursor emitted by the OTHER sort — answers
     * the same VAL-001 taxonomy.
     */
    private KeysetScrollPosition keysetPosition(String after, OrderSort sort) {
        return switch (sort.field()) {
            case CREATED_AT -> KeysetCursorGate.forwardKeyset(cursorStrategy, after, "placedAt",
                    KeysetCursorGate::instantValue, "orders with the same sortBy");
            case LAST_MODIFIED_AT -> KeysetCursorGate.forwardKeyset(cursorStrategy, after, "updatedAt",
                    KeysetCursorGate::instantValue, "orders with the same sortBy");
            case STATUS -> KeysetCursorGate.forwardKeyset(cursorStrategy, after, "status",
                    KeysetCursorGate::textValue, "orders with the same sortBy");
        };
    }

    /**
     * The connection envelope — the declared (not runtime-generated)
     * Saleor shape, built from the Window's own per-element scroll
     * positions (the exact positions the framework's connection
     * adaptation emits for its generated connections — one cursor
     * strategy, one cursor semantics across the whole surface).
     * {@code hasPreviousPage} is the forward-only honest fact: a scroll
     * that started after a known element has elements before it.
     * {@code totalCount} is the population's count (GraphQL Int's signed
     * 32-bit ceiling documented, hence the clamp).
     */
    private OrderCountableConnection toConnection(OrderPage page, KeysetScrollPosition position) {
        Window<OrderView> window = page.window();
        List<OrderView> content = window.getContent();
        List<OrderCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(OrderCountableEdge.newBuilder()
                    .node(mapper.toGraphQlOrder(content.get(i)))
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
        return OrderCountableConnection.newBuilder()
                .edges(edges)
                .pageInfo(pageInfo)
                .totalCount((int) Math.min(page.total(), Integer.MAX_VALUE))
                .build();
    }

    /**
     * The authenticated caller — the house pattern (the /graphql chain
     * guarantees authentication; the guard keeps the fetcher honest if a
     * future public journey reopens the route policy). The ADMIN
     * capability check itself lives in the owning use case — this guard
     * only carries the caller there.
     */
    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required for order reads");
        }
        return authentication;
    }
}
