package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.OrderDirection;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.Voucher;
import com.marketplace.app.graphql.generated.types.VoucherCountableConnection;
import com.marketplace.app.graphql.generated.types.VoucherCountableEdge;
import com.marketplace.app.graphql.generated.types.VoucherFilterInput;
import com.marketplace.app.graphql.generated.types.VoucherSortField;
import com.marketplace.app.graphql.generated.types.VoucherSortingInput;
import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.pricing.spi.VoucherPage;
import com.marketplace.pricing.spi.VoucherSort;
import com.marketplace.pricing.spi.VoucherView;
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
 * Saleor Discounts-domain voucher read roots — {@code voucher} and
 * {@code vouchers} — over the pricing owner's use cases through the
 * declared {@code DiscountReadSpi} seam. This class owns ONLY transport
 * mapping: global-ID decode/encode, argument validation, cursor/position
 * mapping and the connection envelope. Authorization lives in the owning
 * use case (the ADMIN-enforced {@code DiscountReadService} — the
 * platform's server-side mapping of Saleor's staff MANAGE_DISCOUNTS
 * requirement at the use-case boundary, the RBAC divergence row), never
 * in a UI route guard and never duplicated here.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code voucher} executes its {@code id} argument through the typed
 * global-ID codec, while its {@code externalReference} argument (no
 * external-ID column exists on this platform) and its {@code channel}
 * argument (no channel model exists — the PENDING-ADR commercial-context
 * row) answer the explicit classified not-implemented error — never a
 * silent no-op. {@code vouchers} executes forward-only keyset paging
 * ({@code first}/{@code after}) in the one sort with an owning non-null
 * keyset column (CODE — deprecated upstream but the only owned
 * deterministic keyset field on this platform; NAME/START_DATE/
 * END_DATE/VALUE/TYPE/USAGE_LIMIT/MINIMUM_SPENT_AMOUNT answer the
 * explicit classified error, as does the deprecated nested
 * {@code sortBy.channel}) and answers explicit classified errors for
 * the declared wire arguments whose owning capability does not exist
 * yet ({@code filter}, the deprecated {@code query}, {@code channel},
 * {@code before}/{@code last}) — recorded divergences, never silent
 * no-ops and never fake successes.</p>
 */
@DgsComponent
public class VoucherDataFetcher {

    /**
     * The default voucher sort when the caller sends none — the policy
     * code ordering ascending (Saleor's own Voucher model ordering by
     * code), deterministic with the {@code id} tiebreaker.
     */
    private static final VoucherSort DEFAULT_SORT =
            VoucherSort.of(VoucherSort.Field.CODE, true);

    private final DiscountReadSpi discountReadSpi;
    private final VoucherGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public VoucherDataFetcher(DiscountReadSpi discountReadSpi,
                              VoucherGraphQlMapper mapper,
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
     * Saleor {@code vouchers(...): VoucherCountableConnection} — the
     * voucher policy collection as a forward-only keyset scroll (the
     * staffUsers/permissionGroups/productTypes/promotions keyset
     * precedent: cursors identify a row's sort key, never an absolute
     * offset) inside Saleor's exact countable-connection envelope,
     * including {@code totalCount}.
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): the boundary is
     * tested, recorded in the divergence registry, and never a silent
     * no-op. An empty or non-positive {@code first} answers the empty
     * page (the staffUsers field's own house contract), so {@code
     * totalCount} answers the honest not-computed null — never a
     * fabricated zero total.</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.Vouchers)
    @RateLimiter(name = "graphqlVoucherQuery")
    public VoucherCountableConnection vouchers(
            @InputArgument VoucherFilterInput filter,
            @InputArgument VoucherSortingInput sortBy,
            @InputArgument String query,
            @InputArgument String channel,
            @InputArgument String before,
            @InputArgument String after,
            @InputArgument Integer first,
            @InputArgument Integer last) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "vouchers filter is not implemented on this platform yet");
        }
        if (query != null) {
            throw new IllegalArgumentException(
                    "vouchers query is not implemented on this platform yet");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "vouchers channel is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "vouchers supports forward-only pagination (first/after) on this platform");
        }

        VoucherSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            // The degenerate page: no query executes (the staffUsers field's
            // own house contract), so totalCount answers the honest
            // not-computed null — never a fabricated zero total.
            return VoucherCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = keysetPosition(after);
        VoucherPage page = discountReadSpi.scrollVouchers(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * Saleor {@code voucher(id: ID, externalReference: String, channel:
     * String): Voucher} — the staff lookup, ADMIN-enforced in the pricing
     * use case (Saleor requires MANAGE_DISCOUNTS — the mapped platform
     * capability). The {@code id} argument executes through the typed
     * global-ID codec: an unknown but well-formed lookup answers null
     * (the safe absence); a malformed or mistyped global ID answers the
     * codec's classified validation error — never a silent null and
     * never a raw UUID fallback. The {@code externalReference} argument
     * (no external-ID column) and the {@code channel} argument (no
     * channel model) answer the explicit classified not-implemented
     * error — never a silent no-op.
     */
    @DgsQuery(field = DgsConstants.QUERY.Voucher)
    @RateLimiter(name = "graphqlVoucherQuery")
    public Voucher voucher(@InputArgument String id,
                           @InputArgument String externalReference,
                           @InputArgument String channel) {
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "voucher externalReference is not implemented on this platform yet");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "voucher channel is not implemented on this platform yet");
        }
        if (id == null) {
            throw new IllegalArgumentException(
                    "voucher requires an id on this platform (externalReference is not implemented yet)");
        }

        Authentication authentication = currentAuthentication();
        UUID nativeId = globalIdCodec.decode(id, VoucherGraphQlMapper.VOUCHER_TYPE);
        return discountReadSpi.findVoucherById(nativeId, authentication)
                .map(mapper::toGraphQlVoucher)
                .orElse(null);
    }

    /**
     * The sort mapping — Saleor's {@code VoucherSortingInput} onto the
     * owner's executed keyset sort. CODE has an owning non-null column
     * and executes; the deprecated nested {@code channel} (no channel
     * model) and every other field — NAME/START_DATE/END_DATE (optional
     * columns that cannot carry deterministic keyset cursors), VALUE/
     * MINIMUM_SPENT_AMOUNT (channel-scoped money upstream, forbidden as
     * authoritative amounts on this wire by the frozen money contract),
     * TYPE (no voucher-type discrimination exists) — answer the explicit
     * classified not-implemented error: never a silent fallback to
     * another sort.
     */
    private static VoucherSort toOwnerSort(VoucherSortingInput sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        if (sortBy.getChannel() != null) {
            throw new IllegalArgumentException(
                    "vouchers sortBy.channel is not implemented on this platform yet");
        }
        boolean ascending = sortBy.getDirection() == null || OrderDirection.ASC.equals(sortBy.getDirection());
        VoucherSortField field = sortBy.getField() == null
                ? VoucherSortField.CODE
                : sortBy.getField();
        return switch (field) {
            case CODE -> VoucherSort.of(VoucherSort.Field.CODE, ascending);
            case NAME, START_DATE, END_DATE, VALUE, TYPE, USAGE_LIMIT, MINIMUM_SPENT_AMOUNT ->
                    throw new IllegalArgumentException(
                            "vouchers sort field " + field + " is not implemented on this platform yet");
        };
    }

    /**
     * The ONE shared cursor gate (the house KeysetCursorGate): the key set
     * is the sort's own (code, id) pair with the property's declared
     * value type, so a client-crafted cursor with well-named but
     * unparseable values answers the same VAL-001 taxonomy.
     */
    private KeysetScrollPosition keysetPosition(String after) {
        return KeysetCursorGate.forwardKeyset(cursorStrategy, after, "code", KeysetCursorGate::textValue,
                "vouchers with the same sortBy");
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
    private VoucherCountableConnection toConnection(VoucherPage page, KeysetScrollPosition position) {
        Window<VoucherView> window = page.window();
        List<VoucherView> content = window.getContent();
        List<VoucherCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(VoucherCountableEdge.newBuilder()
                    .node(mapper.toGraphQlVoucher(content.get(i)))
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
        return VoucherCountableConnection.newBuilder()
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
                    "Authentication is required for voucher administration");
        }
        return authentication;
    }
}
