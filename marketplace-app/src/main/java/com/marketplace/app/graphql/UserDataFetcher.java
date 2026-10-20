package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.app.graphql.generated.types.StaffUserInput;
import com.marketplace.app.graphql.generated.types.User;
import com.marketplace.app.graphql.generated.types.UserCountableConnection;
import com.marketplace.app.graphql.generated.types.OrderDirection;
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
import java.util.Optional;
import java.util.UUID;

/**
 * P1 (the canonical Saleor plan §5-P1): the GraphQL adapter for the
 * Saleor Users-domain read roots — {@code me}, {@code user} and
 * {@code staffUsers} — over the identity owner's use cases through the
 * declared {@code identity-spi} seam. This class owns ONLY transport
 * mapping: global-ID decode/encode, argument validation, cursor/position
 * mapping and the connection envelope. Authorization lives in the owning
 * use case (the ADMIN-enforced identity service methods — the plan's
 * "check permissions at service/use-case boundaries, not only in React"),
 * never in a UI route guard and never duplicated here.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code user} executes its id/email/externalReference arguments (the
 * external reference is the platform's OIDC subject of record);
 * {@code staffUsers} executes forward-only keyset paging
 * ({@code first}/{@code after}) in the three sorts with an owning column
 * (EMAIL/CREATED_AT/LAST_MODIFIED_AT, deterministic with the {@code id}
 * tiebreaker) and answers explicit classified errors for the declared
 * wire arguments whose owning capability does not exist yet
 * ({@code filter}, {@code before}/{@code last}, the unowned sort fields) —
 * a recorded divergence, never a silent no-op and never a fake success.</p>
 */
@DgsComponent
public class UserDataFetcher {

    /**
     * The default staff-list sort when the caller sends none — Saleor's
     * own User model ordering (email ascending), deterministic with the
     * {@code id} tiebreaker.
     */
    private static final UserAccountSort DEFAULT_SORT = UserAccountSort.of(UserAccountSort.Field.EMAIL, true);

    private final IdentitySpi identitySpi;
    private final UserGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public UserDataFetcher(IdentitySpi identitySpi,
                           UserGraphQlMapper mapper,
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
     * Saleor {@code me: User} — the authenticated caller's own stored
     * profile projection (read-only; the REST {@code /me} bootstrap keeps
     * its own documented path). Nullable exactly as the Saleor root: a
     * subject with no users row yet answers null — the honest absence.
     */
    @DgsQuery(field = DgsConstants.QUERY.Me)
    @RateLimiter(name = "graphqlUserQuery")
    public User me() {
        return identitySpi.findCurrentUserAccount(currentAuthentication())
                .map(mapper::toGraphQlUser)
                .orElse(null);
    }

    /**
     * Saleor {@code user(id, email, externalReference): User} — the
     * staff/user lookup, ADMIN-enforced in the identity use case (Saleor
     * requires MANAGE_STAFF/MANAGE_USERS/MANAGE_ORDERS — the catalog's
     * mapped platform capability). Exactly one argument is accepted: the
     * Saleor resolver's own at-least-one requirement, tightened to
     * exactly-one so no argument is ever silently ignored. An unknown but
     * well-formed lookup answers null (the safe absence); a malformed or
     * mistyped global ID answers the codec's classified validation error.
     */
    @DgsQuery(field = DgsConstants.QUERY.User)
    @RateLimiter(name = "graphqlUserQuery")
    public User user(@InputArgument String id,
                     @InputArgument String email,
                     @InputArgument String externalReference) {
        int providedCount = (id != null ? 1 : 0) + (email != null ? 1 : 0) + (externalReference != null ? 1 : 0);
        if (providedCount == 0) {
            throw new IllegalArgumentException("user requires exactly one of id, email or externalReference");
        }
        if (providedCount > 1) {
            throw new IllegalArgumentException("user accepts exactly one of id, email or externalReference");
        }

        Authentication authentication = currentAuthentication();
        Optional<UserAccountView> view;
        if (id != null) {
            UUID nativeId = globalIdCodec.decode(id, UserGraphQlMapper.USER_TYPE);
            view = identitySpi.findUserAccountById(nativeId, authentication);
        } else if (email != null) {
            view = identitySpi.findUserAccountByEmail(email, authentication);
        } else {
            view = identitySpi.findUserAccountBySubject(externalReference, authentication);
        }
        return view.map(mapper::toGraphQlUser).orElse(null);
    }

    /**
     * Saleor {@code staffUsers(...): UserCountableConnection} — the
     * staff/user roster as a forward-only keyset scroll (the
     * storeProducts/services keyset precedent: cursors identify a row's
     * sort key, never an absolute offset) inside Saleor's exact
     * countable-connection envelope, including {@code totalCount}.
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): the boundary is
     * tested, recorded in the divergence registry, and never a silent
     * no-op. An empty or non-positive {@code first} answers the empty
     * page (the services field's own house contract).</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.StaffUsers)
    @RateLimiter(name = "graphqlUserQuery")
    public UserCountableConnection staffUsers(
            @InputArgument Integer first,
            @InputArgument String after,
            @InputArgument String before,
            @InputArgument Integer last,
            @InputArgument UserSortingInput sortBy,
            @InputArgument StaffUserInput filter) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "staffUsers filter is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "staffUsers supports forward-only pagination (first/after) on this platform");
        }

        UserAccountSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            // The degenerate page: no query executes (the services field's
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
        UserAccountPage page = identitySpi.scrollStaffUsers(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * The sort mapping — Saleor's {@code UserSortingInput} onto the owner's
     * executed keyset sorts. The fields with an owning column execute;
     * the rest answer the explicit not-supported error (FIRST_NAME and
     * LAST_NAME have no owning columns, ORDER_COUNT and RANK belong to
     * later waves' owning queries — the recorded boundary).
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
                    "staffUsers sort field " + field + " is not implemented on this platform yet");
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
                "staffUsers with the same sortBy");
    }

    /**
     * The connection envelope — the declared (not runtime-generated)
     * Saleor shape, built from the Window's own per-element scroll
     * positions (the exact positions the framework's connection
     * adaptation emits for its generated connections — one cursor
     * strategy, one cursor semantics across the whole surface).
     * {@code hasPreviousPage} is the forward-only honest fact: a scroll
     * that started after a known element has elements before it.
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
                    .cursor(cursorStrategy.encode(window.positionAt(i)))
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
                    "Authentication is required for user administration");
        }
        return authentication;
    }

}
