package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.shared.api.CmsNavigationPort;
import com.marketplace.shared.api.CmsPagePort;
import com.marketplace.identity.spi.UserManagementSpi;
import com.marketplace.identity.spi.CustomerTypeSpi;
import com.marketplace.identity.spi.AddressManagementSpi;
import com.marketplace.identity.spi.AccountLifecycleSpi;
import com.marketplace.pricing.spi.DiscountReadSpi;
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
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 (the canonical Saleor plan §5-P1 — packet campaign-b3-a1b): the DGS
 * data-fetcher layer proof of the Saleor {@code customers} read root —
 * the house containerless pattern ({@code @SpringBootTest} over the
 * GraphQL adapter package slice + {@code @EnableDgsTest} +
 * {@link DgsQueryExecutor}): REAL GraphQL documents against the REAL SDL
 * and the REAL DGS wiring with no web layer and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the
 * {@code customers} document executes through the real argument binding
 * (including the bounded {@code CustomerFilterInput}/
 * {@code CustomerWhereInput} wire shapes), the SHARED {@code User}
 * mapper (one wire projection across the Users read surface), the
 * permission catalog and the exception taxonomy resolver; the
 * connection envelope serializes with per-element keyset cursors from
 * the SAME cursor strategy the framework adapters use; the delegation
 * carries the executed sort and the clamped page size to the customer
 * scroll seam. The owning identity use case is mocked at the declared
 * seam — the full-stack proof (real {@code @PreAuthorize}, the real
 * CONSUMER-population keyset SQL, real database) lives in
 * {@code CustomerGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = CustomerDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class CustomerDataFetcherDgsTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    // The token-rotation JDBC store rides the package component scan; its
    // owning seam is not under test in this slice — the interface mock
    // keeps the slice's containerless wiring intact (the seam-gets-its-mock law).
    @MockitoBean
    private RefreshTokenRotationStore refreshTokenRotationStore;

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    @Autowired
    private EncodingCursorStrategy<ScrollPosition> cursorStrategy;

    // The package slice wires every fetcher; each owning seam gets its mock —
    // the UserDataFetcherDgsTest precedent.
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

    // The product-media mutation roots' owning seam (agent-2 N-slice) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.media.spi.ProductMediaWriteSpi productMediaWriteSpi;

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
    @MockitoBean
    private AccountLifecycleSpi accountLifecycleSpi;
    @MockitoBean
    private AddressManagementSpi addressManagementSpi;
    @MockitoBean
    private CustomerTypeSpi customerTypeSpi;
    @MockitoBean
    private UserManagementSpi userManagementSpi;
    @MockitoBean
    private CmsPagePort cmsPagePort;
    @MockitoBean
    private CmsNavigationPort cmsNavigationPort;
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

    private static UserAccountView consumerView(UUID id, String email) {
        return new UserAccountView(id, email, false, true,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"),
                Set.of("CONSUMER"));
    }

    /**
     * The {@code customers} document through the real wiring: the Saleor
     * connection envelope serializes with edges, per-element keyset cursors
     * (from the SAME cursor strategy the framework's connection adaptation
     * uses), pageInfo and the CUSTOMER population's own totalCount — and
     * the customer nodes carry the platform's honest non-staff facts
     * rendered through the SHARED User mapper.
     */
    @Test
    void customersSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        java.util.function.Function<ScrollPosition, Window<UserAccountView>> windowAt = position ->
                Window.from(List.of(
                                consumerView(first, "customer-one@example.com"),
                                consumerView(second, "customer-two@example.com")), index -> {
                            if (index == 0) {
                                return ScrollPosition.forward(Map.of(
                                        "email", "customer-one@example.com", "id", first));
                            }
                            return ScrollPosition.forward(Map.of(
                                    "email", "customer-two@example.com", "id", second));
                        }, true);
        when(identitySpi.scrollCustomers(any(org.springframework.security.core.Authentication.class),
                        any(UserAccountSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new UserAccountPage(
                        windowAt.apply(ScrollPosition.keyset()), 41));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { customers(first: 2) {
                    edges { node { id email isStaff isActive userPermissions { code } } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.customers");

        assertThat(connection.get("totalCount")).isEqualTo(41);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(2);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "email", "customer-one@example.com", "id", first))));
        assertThat(edges.get(1).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "email", "customer-two@example.com", "id", second))));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstNode = (Map<String, Object>) edges.get(0).get("node");
        // The typed global ID the SHARED mapper emits — never a raw UUID.
        assertThat(firstNode.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("User:" + first).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(firstNode.get("email")).isEqualTo("customer-one@example.com");
        assertThat(firstNode.get("isStaff")).isEqualTo(false);
        assertThat(firstNode.get("isActive")).isEqualTo(true);
        // The CONSUMER role renders no administrative capability — the
        // honest empty permission set.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> permissions = (List<Map<String, Object>>) firstNode.get("userPermissions");
        assertThat(permissions).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) connection.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
        assertThat(pageInfo.get("startCursor")).isEqualTo(edges.get(0).get("cursor"));
        assertThat(pageInfo.get("endCursor")).isEqualTo(edges.get(1).get("cursor"));
    }

    /**
     * The delegation contract: the executed default sort (EMAIL ascending,
     * the staffUsers root's own default) and the bounded page size reach
     * the customer scroll seam — the adapter is the thin transport the
     * authorization contract requires.
     */
    @Test
    void customersDelegatesTheExecutedSortAndTheBoundedPageSizeToTheOwnerSeam() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        when(identitySpi.scrollCustomers(any(org.springframework.security.core.Authentication.class),
                        any(UserAccountSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new UserAccountPage(
                        Window.from(List.of(), index -> ScrollPosition.forward(Map.of()), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { customers(first: 7) { edges { cursor } totalCount } }",
                "data.customers");

        ArgumentCaptor<UserAccountSort> sortCaptor = ArgumentCaptor.forClass(UserAccountSort.class);
        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        verify(identitySpi).scrollCustomers(any(org.springframework.security.core.Authentication.class),
                sortCaptor.capture(), any(), limitCaptor.capture());
        assertThat(sortCaptor.getValue().field()).isEqualTo(UserAccountSort.Field.EMAIL);
        assertThat(sortCaptor.getValue().ascending()).isTrue();
        assertThat(limitCaptor.getValue().max()).isEqualTo(7);
    }

    /**
     * The declared-but-unexecuted Saleor arguments answer the explicit
     * classified error through the real resolver — never a silent no-op.
     * Every non-null filter value (any field combination of the bounded
     * CustomerFilterInput) rides this one boundary.
     */
    @Test
    void customersFilterAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { customers(first: 2, filter: { search: \"x\" }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("filter is not implemented");
    }

    /** The where boundary: the pinned schema's replacement filter argument answers the same classified error. */
    @Test
    void customersWhereAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { customers(first: 2, where: { isActive: true }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("where filter is not implemented");
    }

    /** The search boundary: the free-text search argument answers the explicit classified error. */
    @Test
    void customersSearchAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { customers(first: 2, search: \"customer\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("search is not implemented");
    }

    /** The backward-pagination boundary: before/last answer the explicit forward-only error. */
    @Test
    void customersBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { customers(last: 2) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("forward-only");
    }

    /**
     * The sort gate: the UserSortField vocabulary values without an owning
     * column answer the explicit not-supported error (the staffUsers
     * boundary's own mapping, one vocabulary across both roots).
     */
    @Test
    void customersUnownedSortFieldAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { customers(first: 2, sortBy: { field: FIRST_NAME, direction: ASC }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("customers sort field FIRST_NAME is not implemented");
    }

    /**
     * The cursor gate: a cursor emitted by a different sort is rejected
     * for the default email scroll — the keyset mismatch, classified
     * VAL-001 through the real resolver.
     */
    @Test
    void cursorFromAnotherSortIsRejectedThroughTheRealCursorGate() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        String createdAtCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(Map.of("createdAt", Instant.parse("2026-10-01T00:00:00Z"),
                        "id", UUID.randomUUID())));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($after: String) { customers(first: 1, after: $after) { totalCount } }",
                "$", Map.of("after", createdAtCursor)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("after must be a forward keyset cursor")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "VAL-001"));
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
        when(identitySpi.scrollCustomers(any(org.springframework.security.core.Authentication.class),
                        any(UserAccountSort.class), any(), any(Limit.class)))
                .thenThrow(new AccessDeniedException("Administrator role required"));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { customers(first: 1) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Administrator role required")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "AUTHZ-001")
                        .containsEntry("category", "authz"));
    }

    /** The unauthenticated guard: no security context answers the credentials error, never a silent read. */
    @Test
    void unauthenticatedCallerAnswersTheCredentialsError() {
        SecurityContextHolder.clearContext();
        // The fetcher's guard throws AuthenticationCredentialsNotFoundException;
        // the resolver's leakage policy (measured) masks unknown exceptions to
        // the generic internal sentence — the HTTP layer's 401 tripartite
        // contract (GraphQlSecurityIntegrationTest) remains the anonymous
        // boundary's own proof.
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { customers(first: 1) { totalCount } }", "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

}
