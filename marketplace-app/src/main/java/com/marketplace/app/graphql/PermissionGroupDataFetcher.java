package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.Group;
import com.marketplace.app.graphql.generated.types.GroupCountableConnection;
import com.marketplace.app.graphql.generated.types.GroupCountableEdge;
import com.marketplace.app.graphql.generated.types.OrderDirection;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.PermissionGroupFilterInput;
import com.marketplace.app.graphql.generated.types.PermissionGroupSortField;
import com.marketplace.app.graphql.generated.types.PermissionGroupSortingInput;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.identity.spi.PermissionGroupPage;
import com.marketplace.identity.spi.PermissionGroupSort;
import com.marketplace.identity.spi.PermissionGroupView;
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
 * P1 (the canonical Saleor plan §5-P1): the GraphQL adapter for the
 * Saleor Users-domain permission-group read roots — {@code permissionGroups}
 * and {@code permissionGroup} — over the identity owner's use cases through
 * the declared {@code identity-spi} seam. This class owns ONLY transport
 * mapping: global-ID decode/encode, argument validation, cursor/position
 * mapping and the connection envelope. Authorization lives in the owning
 * use case (the ADMIN-enforced identity service methods — Saleor requires
 * MANAGE_STAFF, mapped to the platform's ADMIN capability per the merged
 * {@code SaleorPermissionCatalog} contract), never in a UI route guard and
 * never duplicated here.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code permissionGroups} executes forward-only keyset paging
 * ({@code first}/{@code after}) in the one sort the Saleor
 * {@code PermissionGroupSortingInput} vocabulary declares (NAME — an owned
 * field, deterministic with the {@code id} tiebreaker; the Saleor enum
 * closes over NAME alone, so no unowned sort-field branch exists on this
 * surface to answer) and answers explicit classified errors for the
 * declared wire arguments whose owning capability does not exist yet
 * ({@code filter}, {@code before}/{@code last}) — a recorded divergence,
 * never a silent no-op and never a fake success. {@code permissionGroup}
 * executes its {@code id} argument through the typed global-ID codec: an
 * unknown but well-formed ID answers null (the safe absence), a malformed
 * or mistyped global ID answers the codec's classified validation
 * error.</p>
 */
@DgsComponent
public class PermissionGroupDataFetcher {

    /**
     * The default permission-group sort when the caller sends none — the
     * group display-name ordering ascending (Saleor's own Group model
     * ordering by name), deterministic with the {@code id} tiebreaker.
     */
    private static final PermissionGroupSort DEFAULT_SORT =
            PermissionGroupSort.of(PermissionGroupSort.Field.NAME, true);

    private final IdentitySpi identitySpi;
    private final PermissionGroupGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public PermissionGroupDataFetcher(IdentitySpi identitySpi,
                                      PermissionGroupGraphQlMapper mapper,
                                      GraphQlProperties properties,
                                      EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                      SaleorGlobalIdCodec globalIdCodec) {
        this.identitySpi = identitySpi;
        this.mapper = mapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code permissionGroups(...): GroupCountableConnection} — the
     * permission-group collection as a forward-only keyset scroll (the
     * staffUsers keyset precedent: cursors identify a row's sort key, never
     * an absolute offset) inside Saleor's exact countable-connection
     * envelope, including {@code totalCount}.
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): the boundary is
     * tested, recorded in the divergence registry, and never a silent
     * no-op. An empty or non-positive {@code first} answers the empty
     * page (the staffUsers field's own house contract).</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.PermissionGroups)
    @RateLimiter(name = "graphqlPermissionGroupQuery")
    public GroupCountableConnection permissionGroups(
            @InputArgument Integer first,
            @InputArgument String after,
            @InputArgument String before,
            @InputArgument Integer last,
            @InputArgument PermissionGroupSortingInput sortBy,
            @InputArgument PermissionGroupFilterInput filter) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "permissionGroups filter is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "permissionGroups supports forward-only pagination (first/after) on this platform");
        }

        PermissionGroupSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            // The degenerate page: no query executes (the staffUsers field's
            // own house contract), so totalCount answers the honest
            // not-computed null — never a fabricated zero total.
            return GroupCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = keysetPosition(after);
        PermissionGroupPage page = identitySpi.scrollPermissionGroups(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * Saleor {@code permissionGroup(id: ID!): Group} — the staff lookup,
     * ADMIN-enforced in the identity use case (Saleor requires
     * MANAGE_STAFF — the catalog's mapped platform capability). The
     * {@code id} argument executes through the typed global-ID codec: an
     * unknown but well-formed lookup answers null (the safe absence); a
     * malformed or mistyped global ID answers the codec's classified
     * validation error — never a silent null and never a raw UUID
     * fallback.
     */
    @DgsQuery(field = DgsConstants.QUERY.PermissionGroup)
    @RateLimiter(name = "graphqlPermissionGroupQuery")
    public Group permissionGroup(@InputArgument String id) {
        Authentication authentication = currentAuthentication();
        UUID nativeId = globalIdCodec.decode(id, PermissionGroupGraphQlMapper.GROUP_TYPE);
        return identitySpi.findPermissionGroupById(nativeId, authentication)
                .map(mapper::toGraphQlGroup)
                .orElse(null);
    }

    /**
     * The sort mapping — Saleor's {@code PermissionGroupSortingInput} onto
     * the owner's executed keyset sort. The Saleor
     * {@code PermissionGroupSortField} vocabulary closes over exactly one
     * value (NAME) and it is owned, so every declared sort field executes;
     * there is no unowned branch on this surface to answer with the
     * not-supported error (the staffUsers boundary's own structural
     * difference — documented, not faked).
     */
    private static PermissionGroupSort toOwnerSort(PermissionGroupSortingInput sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        boolean ascending = sortBy.getDirection() == null || OrderDirection.ASC.equals(sortBy.getDirection());
        PermissionGroupSortField field = sortBy.getField() == null
                ? PermissionGroupSortField.NAME
                : sortBy.getField();
        return switch (field) {
            case NAME -> PermissionGroupSort.of(PermissionGroupSort.Field.NAME, ascending);
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
                "permissionGroups with the same sortBy");
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
    private GroupCountableConnection toConnection(PermissionGroupPage page, KeysetScrollPosition position) {
        Window<PermissionGroupView> window = page.window();
        List<PermissionGroupView> content = window.getContent();
        List<GroupCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(GroupCountableEdge.newBuilder()
                    .node(mapper.toGraphQlGroup(content.get(i)))
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
        return GroupCountableConnection.newBuilder()
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
                    "Authentication is required for permission-group administration");
        }
        return authentication;
    }

}
