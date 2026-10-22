package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.identity.spi.UserAccountPage;
import com.marketplace.identity.spi.UserAccountSort;
import com.marketplace.identity.spi.UserAccountView;
import com.marketplace.shared.security.CurrentUserProvider;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P1 (the canonical Saleor plan §5-P1): the DGS data-fetcher layer proof
 * of the Saleor Users read roots — the house containerless pattern
 * ({@code @SpringBootTest} over the GraphQL adapter package slice +
 * {@code @EnableDgsTest} + {@link DgsQueryExecutor}): REAL GraphQL
 * documents against the REAL SDL and the REAL DGS wiring with no web
 * layer and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the context
 * itself booting is the first proof — the hand-declared
 * {@code UserCountableConnection}/{@code UserCountableEdge}/{@code
 * PageInfo} (Saleor's exact wire shape, {@code totalCount} included) load
 * through the DGS bridge while the runtime
 * {@code ConnectionTypeDefinitionConfigurer} keeps generating the OTHER
 * connection types (the declared-types-skip behavior measured from the
 * spring-graphql 2.0.5 source). Then: the {@code me}/{@code user}/{@code
 * staffUsers} documents execute through the real argument binding, the
 * typed global-ID codec, the mapper, the permission catalog and the
 * exception taxonomy resolver; the connection envelope serializes with
 * per-element keyset cursors from the SAME cursor strategy the framework
 * adapters use. The owning identity use cases are mocked at the declared
 * seam — the full-stack proof (real {@code @PreAuthorize}, real keyset
 * SQL, real database) lives in {@code UserGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = UserDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class UserDataFetcherDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    @Autowired
    private EncodingCursorStrategy<ScrollPosition> cursorStrategy;

    // The package slice wires every fetcher; each owning seam gets its mock —
    // the ServiceDgsDataFetcherTest precedent.
    @MockitoBean
    private CatalogSpi catalogSpi;

    @MockitoBean
    private ProductAdminSpi productAdminSpi;

    @MockitoBean
    private DiscoverySpi discoverySpi;

    @MockitoBean
    private NeighborhoodFeedPort neighborhoodFeedPort;

    @MockitoBean
    private NeighborhoodPostDetailPort postDetailPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private IdentitySpi identitySpi;

    @MockitoBean
    private ProductVariantReadSpi variantReadSpi;

    // The product-variant mutation roots' owning write seam (agent-2
    // follow-on packet) — the package slice wires every fetcher, so
    // this seam gets its mock (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantWriteSpi productVariantWriteSpi;

    // The translation mutation roots' owning write seam (agent-2
    // follow-on packet) — the package slice wires every fetcher, so
    // this seam gets its mock (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.TranslationWriteSpi translationWriteSpi;

    // The product-type attribute schema mutation roots' owning write
    // seam (agent-2 follow-on packet) — the package slice wires every
    // fetcher, so this seam gets its mock (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeAttributeWriteSpi productTypeAttributeWriteSpi;

    @MockitoBean
    private OrderReadSpi orderReadSpi;

    @MockitoBean
    private ProductReadSpi productReadSpi;

    @MockitoBean
    private CategoryReadSpi categoryReadSpi;
    // The category mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private CategoryWriteSpi categoryWriteSpi;

    // The collection read/mutation roots' owning seams (agent-2 L-slice) —
    // the package slice wires every fetcher, so these seams get their
    // mocks (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionReadSpi collectionReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.CollectionWriteSpi collectionWriteSpi;
    // The promotion/voucher read roots' owning seam (b5-b packet) — the
    // sibling-test mock the packet's own precedent prescribed but did not
    // complete; the package slice context cannot boot without it.
    @MockitoBean
    private com.marketplace.pricing.spi.DiscountReadSpi discountReadSpi;
    // The package slice wires every fetcher; every seam the slice's other
    // fetchers need gets its mock — the incomplete sibling fixes the
    // b4/b5 packets left behind (committed against a red CI) completed
    // here so the shared slice context can boot at all.
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeReadSpi attributeReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeReadSpi productTypeReadSpi;
    // The voucher mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.pricing.spi.VoucherWriteSpi voucherWriteSpi;

    // The product-type and product mutation roots' owning write seams
    // (agent-2 packet) — the package slice wires every fetcher, so
    // these seams get their mocks (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeWriteSpi productTypeWriteSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductWriteSpi productWriteSpi;


    // The attribute mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeWriteSpi attributeWriteSpi;

    // The attribute-value mutation roots' owning write seam (agent-2
    // packet) — the package slice wires every fetcher, so this seam
    // gets its mock (the b5-a sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.AttributeValueWriteSpi attributeValueWriteSpi;
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("roles", List.of(roles))
                .build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                jwt, List.of(java.util.Arrays.stream(roles).map(r -> "ROLE_" + r)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .<org.springframework.security.core.GrantedAuthority>toList()
                        .toArray(new org.springframework.security.core.GrantedAuthority[0])),
                subject);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static UserAccountView adminView(UUID id) {
        return new UserAccountView(id, "admin@example.com", true, true,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"),
                Set.of("ADMIN"));
    }

    private static UserAccountView providerView(UUID id) {
        return new UserAccountView(id, "provider@example.com", false, true,
                Instant.parse("2026-10-03T00:00:00Z"), Instant.parse("2026-10-04T00:00:00Z"),
                Set.of("PROVIDER"));
    }

    /**
     * The {@code me} document through the real wiring: the JWT subject
     * resolves the mocked owner read, the codec's typed global ID lands on
     * the wire, and the permission catalog renders the ADMIN matrix.
     */
    @Test
    void meResolvesThroughTheDgsWiringWithTypedGlobalIdAndPermissions() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        when(identitySpi.findCurrentUserAccount(any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(adminView(id)));

        Map<String, Object> me = dgsQueryExecutor.executeAndExtractJsonPath(
                "query { me { id email isStaff isActive dateJoined userPermissions { code name } } }",
                "data.me");

        assertThat(me.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("User:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(me.get("email")).isEqualTo("admin@example.com");
        assertThat(me.get("isStaff")).isEqualTo(true);
        assertThat(me.get("isActive")).isEqualTo(true);
        assertThat(me.get("dateJoined")).isEqualTo("2026-10-01T00:00:00Z");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> permissions = (List<Map<String, Object>>) me.get("userPermissions");
        assertThat(permissions)
                .extracting(p -> p.get("code"))
                .containsExactly("MANAGE_USERS", "MANAGE_STAFF", "MANAGE_ORDERS");
        assertThat(permissions)
                .extracting(p -> p.get("name"))
                .containsExactly("Manage users.", "Manage staff.", "Manage orders.");
    }

    /**
     * The {@code user(id)} document: the REAL argument binding carries the
     * opaque global ID, the codec decodes it (a mistyped raw UUID answers
     * the classified VAL-001 error through the real exception resolver),
     * and the owner read serializes through the mapper.
     */
    @Test
    void userReadsBackByTypedGlobalIdThroughTheRealArgumentBinding() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        when(identitySpi.findUserAccountById(eq(id), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(providerView(id)));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("User:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> user = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { user(id: $id) { id email isStaff userPermissions { code } } }",
                "data.user", Map.of("id", globalId));

        assertThat(user.get("id")).isEqualTo(globalId);
        assertThat(user.get("email")).isEqualTo("provider@example.com");
        assertThat(user.get("isStaff")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> permissions = (List<Map<String, Object>>) user.get("userPermissions");
        assertThat(permissions).extracting(p -> p.get("code")).containsExactly("MANAGE_PRODUCTS");
    }

    /**
     * The codec's classified validation error surfaces through the REAL
     * error taxonomy resolver when a raw UUID is sent where a typed
     * global ID is expected.
     */
    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();

        // The DGS executor surfaces the classified GraphQL error as a
        // QueryException carrying the resolver's mapped message.
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { user(id: $id) { id } }",
                "$", Map.of("id", id.toString())))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
    }

    /**
     * The owning use case's ADMIN denial maps to the AUTHZ-001 taxonomy
     * through the real exception resolver — the same classification the
     * full-stack integration test proves against the real
     * {@code @PreAuthorize}.
     */
    @Test
    void ownerDenialMapsToTheAuthzTaxonomyThroughTheRealResolver() {
        authenticateAs("dgs-provider-subject", "PROVIDER");
        when(identitySpi.findUserAccountById(any(UUID.class), any(org.springframework.security.core.Authentication.class)))
                .thenThrow(new AccessDeniedException("Administrator role required"));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("User:" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { user(id: $id) { id } }",
                "$", Map.of("id", globalId)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Administrator role required")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "AUTHZ-001")
                        .containsEntry("category", "authz"));
    }

    /**
     * The staff list through the real wiring: the Saleor connection
     * envelope serializes with edges, per-element keyset cursors (from
     * the SAME cursor strategy the framework's connection adaptation
     * uses), pageInfo and totalCount — the declared-type proof the
     * context boot already started.
     */
    @Test
    void staffUsersSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        java.util.function.Function<ScrollPosition, Window<UserAccountView>> windowAt = position ->
                Window.from(List.of(adminView(first), providerView(second)), index -> {
                    if (index == 0) {
                        return ScrollPosition.forward(Map.of(
                                "email", "admin@example.com", "id", first));
                    }
                    return ScrollPosition.forward(Map.of(
                            "email", "provider@example.com", "id", second));
                }, true);
        when(identitySpi.scrollStaffUsers(any(org.springframework.security.core.Authentication.class),
                        any(UserAccountSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new UserAccountPage(
                        windowAt.apply(ScrollPosition.keyset()), 57));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { staffUsers(first: 2) {
                    edges { node { email isStaff userPermissions { code } } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.staffUsers");

        assertThat(connection.get("totalCount")).isEqualTo(57);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(2);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "email", "admin@example.com", "id", first))));
        assertThat(edges.get(1).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "email", "provider@example.com", "id", second))));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstNode = (Map<String, Object>) edges.get(0).get("node");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondNode = (Map<String, Object>) edges.get(1).get("node");
        assertThat(firstNode.get("isStaff")).isEqualTo(true);
        assertThat(secondNode.get("isStaff")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) connection.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
        assertThat(pageInfo.get("startCursor")).isEqualTo(edges.get(0).get("cursor"));
        assertThat(pageInfo.get("endCursor")).isEqualTo(edges.get(1).get("cursor"));
    }

    /**
     * The declared-but-unexecuted Saleor arguments answer the explicit
     * classified error through the real resolver — never a silent no-op.
     */
    @Test
    void staffUsersFilterAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { staffUsers(first: 2, filter: { search: \"x\" }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("filter is not implemented");
    }

    /**
     * The sort gate: a cursor emitted by a different sort is rejected for
     * the default email scroll — the keyset mismatch, classified VAL-001
     * through the real resolver.
     */
    @Test
    void cursorFromAnotherSortIsRejectedThroughTheRealCursorGate() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        String createdAtCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(Map.of("createdAt", Instant.parse("2026-10-01T00:00:00Z"),
                        "id", UUID.randomUUID())));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($after: String) { staffUsers(first: 1, after: $after) { totalCount } }",
                "$", Map.of("after", createdAtCursor)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("after must be a forward keyset cursor")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "VAL-001"));
    }

    /** The unauthenticated guard: no security context answers the credentials error, never a silent read. */
    @Test
    void unauthenticatedCallerAnswersTheCredentialsError() {
        SecurityContextHolder.clearContext();
        // The fetcher's guard throws AuthenticationCredentialsNotFoundException;
        // the resolver's leakage policy (measured) masks unknown exceptions to
        // the generic internal sentence — the HTTP layer's 401 tripartite
        // contract remains the anonymous boundary's own proof.
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { me { id } }", "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

}
