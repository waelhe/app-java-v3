package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.CustomerFilterInput;
import com.marketplace.app.graphql.generated.types.CustomerWhereInput;
import com.marketplace.app.graphql.generated.types.OrderDirection;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.UserCountableConnection;
import com.marketplace.app.graphql.generated.types.UserCountableEdge;
import com.marketplace.app.graphql.generated.types.UserSortField;
import com.marketplace.app.graphql.generated.types.UserSortingInput;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.identity.spi.UserAccountPage;
import com.marketplace.identity.spi.UserAccountSort;
import com.marketplace.identity.spi.UserAccountView;
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

/**
 * P1 (the canonical Saleor plan §5-P1 — packet campaign-b3-a1b): the
 * GraphQL adapter for the Saleor Users-domain {@code customers} read root
 * over the identity owner's use case through the declared
 * {@code identity-spi} seam. This class owns ONLY transport mapping:
 * argument validation, cursor/position mapping and the connection
 * envelope — the row mapping itself is the SHARED {@link UserGraphQlMapper}
 * (the same mapper the {@code staffUsers} root rides: one User wire
 * projection, one typed global-ID encode, one permission rendering across
 * the whole Users read surface — no forked mapping logic). Authorization
 * lives in the owning use case (the ADMIN-enforced
 * {@code UserService.scrollCustomers} — Saleor requires
 * MANAGE_ORDERS/MANAGE_USERS, mapped to the platform's ADMIN capability
 * per the merged {@code SaleorPermissionCatalog} contract), never in a UI
 * route guard and never duplicated here.
 *
 * <p><b>The population</b> is the single identity store's customer-account
 * slice — the accounts whose primary role is the platform's
 * customer/consumer role ({@code CONSUMER}) — the complementary
 * customer-account population of the SAME store the {@code staffUsers}
 * roster reads; no duplicate identity store exists behind this root.</p>
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code customers} executes forward-only keyset paging
 * ({@code first}/{@code after}) in the three sorts with an owning column
 * (EMAIL/CREATED_AT/LAST_MODIFIED_AT, deterministic with the {@code id}
 * tiebreaker — the same executed subset {@code staffUsers} owns) and
 * answers explicit classified errors for the declared wire arguments whose
 * owning capability does not exist yet ({@code filter}, {@code where},
 * {@code search}, {@code before}/{@code last}, the unowned sort fields) —
 * a recorded divergence, never a silent no-op and never a fake
 * success.</p>
 */
@DgsComponent
public class CustomerDataFetcher {

    /**
     * The default customer-list sort when the caller sends none — the same
     * User model ordering the staff list defaults to (email ascending),
     * deterministic with the {@code id} tiebreaker.
     */
    private static final UserAccountSort DEFAULT_SORT = UserAccountSort.of(UserAccountSort.Field.EMAIL, true);

    private final IdentitySpi identitySpi;
    private final UserGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;

    public CustomerDataFetcher(IdentitySpi identitySpi,
                               UserGraphQlMapper mapper,
                               GraphQlProperties properties,
                               EncodingCursorStrategy<ScrollPosition> cursorStrategy) {
        this.identitySpi = identitySpi;
        this.mapper = mapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
    }

    /**
     * Saleor {@code customers(...): UserCountableConnection} — the customer
     * population as a forward-only keyset scroll (the staffUsers keyset
     * precedent: cursors identify a row's sort key, never an absolute
     * offset) inside Saleor's exact countable-connection envelope,
     * including {@code totalCount} (the CUSTOMER population's own count,
     * never the whole roster's).
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): the boundary is
     * tested, recorded in the divergence registry, and never a silent
     * no-op. An empty or non-positive {@code first} answers the empty
     * page (the staffUsers field's own house contract).</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.Customers)
    @RateLimiter(name = "graphqlCustomerQuery")
    public UserCountableConnection customers(
            @InputArgument Integer first,
            @InputArgument String after,
            @InputArgument String before,
            @InputArgument Integer last,
            @InputArgument UserSortingInput sortBy,
            @InputArgument CustomerFilterInput filter,
            @InputArgument CustomerWhereInput where,
            @InputArgument String search) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "customers filter is not implemented on this platform yet");
        }
        if (where != null) {
            throw new IllegalArgumentException(
                    "customers where filter is not implemented on this platform yet");
        }
        if (search != null) {
            throw new IllegalArgumentException(
                    "customers search is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "customers supports forward-only pagination (first/after) on this platform");
        }

        UserAccountSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            // The degenerate page: no query executes (the staffUsers field's
            // own house contract), so totalCount answers the honest
            // not-computed null — never a fabricated zero total.
            return UserCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = keysetPosition(after, sort);
        UserAccountPage page = identitySpi.scrollCustomers(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * The sort mapping — Saleor's {@code UserSortingInput} onto the owner's
     * executed keyset sorts (the same mapping the {@code staffUsers} root
     * owns: one vocabulary, one executed subset). The fields with an owning
     * column execute; the rest answer the explicit not-supported error
     * (FIRST_NAME and LAST_NAME have no owning columns, ORDER_COUNT and
     * RANK belong to later waves' owning queries — the recorded boundary).
     */
    private static UserAccountSort toOwnerSort(UserSortingInput sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        boolean ascending = sortBy.getDirection() == null || OrderDirection.ASC.equals(sortBy.getDirection());
        UserSortField field = sortBy.getField() == null ? UserSortField.EMAIL : sortBy.getField();
        return switch (field) {
            case EMAIL -> UserAccountSort.of(UserAccountSort.Field.EMAIL, ascending);
            case CREATED_AT -> UserAccountSort.of(UserAccountSort.Field.CREATED_AT, ascending);
            case LAST_MODIFIED_AT -> UserAccountSort.of(UserAccountSort.Field.LAST_MODIFIED_AT, ascending);
            case FIRST_NAME, LAST_NAME, ORDER_COUNT, RANK -> throw new IllegalArgumentException(
                    "customers sort field " + field + " is not implemented on this platform yet");
        };
    }

    /**
     * The ONE shared cursor gate (the house KeysetCursorGate): the key set
     * is the sort's own (property, id) pair with the property's declared
     * value type, so a client-crafted cursor with well-named but
     * unparseable values answers the same VAL-001 taxonomy.
     */
    private KeysetScrollPosition keysetPosition(String after, UserAccountSort sort) {
        String property = switch (sort.field()) {
            case EMAIL -> "email";
            case CREATED_AT -> "createdAt";
            case LAST_MODIFIED_AT -> "updatedAt";
        };
        java.util.function.Function<Object, Object> sortValue = switch (sort.field()) {
            case EMAIL -> KeysetCursorGate::textValue;
            case CREATED_AT, LAST_MODIFIED_AT -> KeysetCursorGate::instantValue;
        };
        return KeysetCursorGate.forwardKeyset(cursorStrategy, after, property, sortValue,
                "customers with the same sortBy");
    }

    /**
     * The connection envelope — the declared (not runtime-generated)
     * Saleor shape, built from the Window's own per-element scroll
     * positions (the exact positions the framework's connection
     * adaptation emits for its generated connections — one cursor
     * strategy (toCursor/fromCursor), one cursor semantics across the whole
     * surface). {@code hasPreviousPage} is the forward-only honest fact: a
     * scroll that started after a known element has elements before it.
     * {@code totalCount} is the collection count (GraphQL Int's signed
     * 32-bit ceiling documented, hence the clamp).
     */
    private UserCountableConnection toConnection(UserAccountPage page, KeysetScrollPosition position) {
        Window<UserAccountView> window = page.window();
        List<UserAccountView> content = window.getContent();
        List<UserCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(UserCountableEdge.newBuilder()
                    .node(mapper.toGraphQlUser(content.get(i)))
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
        return UserCountableConnection.newBuilder()
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
                    "Authentication is required for customer administration");
        }
        return authentication;
    }

}
