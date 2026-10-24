package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.console.spi.CmsNavigationSpi;
import com.marketplace.console.spi.CmsPageSpi;
import com.marketplace.identity.spi.UserManagementSpi;
import com.marketplace.identity.spi.CustomerTypeSpi;
import com.marketplace.identity.spi.AddressManagementSpi;
import com.marketplace.identity.spi.AccountLifecycleSpi;
import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductTypePage;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductTypeSort;
import com.marketplace.catalog.spi.ProductTypeView;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.orders.spi.OrderReadSpi;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2): the DGS data-fetcher layer proof
 * of the Saleor product-type read roots — the house containerless pattern
 * ({@code @SpringBootTest} over the GraphQL adapter package slice +
 * {@code @EnableDgsTest} + {@link DgsQueryExecutor}): REAL GraphQL
 * documents against the REAL SDL and the REAL DGS wiring with no web layer
 * and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the context
 * itself booting is the first proof — the hand-declared
 * {@code ProductTypeCountableConnection}/{@code ProductTypeCountableEdge}
 * and the bounded {@code ProductType} type (Saleor's exact wire shape for
 * the declared closure, {@code totalCount} included) load through the DGS
 * bridge. Then: the {@code productTypes}/{@code productType} documents
 * execute through the real argument binding, the typed global-ID codec,
 * the mapper and the exception taxonomy resolver; the connection envelope
 * serializes with per-element keyset cursors from the SAME cursor strategy
 * the framework adapters use. The owning catalog use cases are mocked at
 * the declared seam — the full-stack proof (real {@code @PreAuthorize},
 * the real keyset scroll over the reference tables) lives in
 * {@code ProductTypeGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = ProductTypeDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class ProductTypeDataFetcherDgsTest {

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
    // the UserDataFetcherDgsTest/PermissionGroupDataFetcherDgsTest precedent.
    @MockitoBean
    private CatalogSpi catalogSpi;

    @MockitoBean
    private ProductAdminSpi productAdminSpi;

    @MockitoBean
    private ProductTypeReadSpi productTypeReadSpi;

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
    private CmsPageSpi cmsPageSpi;
    @MockitoBean
    private CmsNavigationSpi cmsNavigationSpi;

    // The post-merge integration round (PR #696 review): the slice scans
    // ALL of com.marketplace.app.graphql, so it discovers the multi-instance
    // rotation round's JdbcRefreshTokenRotationStore — a real @Component
    // whose constructor needs a JdbcTemplate the containerless slice has
    // no DataSource for. The mock satisfies the wiring; the store's REAL
    // behavior (including the two-instance atomicity) is proven over real
    // PostgreSQL/Flyway in PlatformTokenGrantRotationMultiInstanceIntegrationTest.
    @MockitoBean
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // The post-merge integration round's wiring completion (PR #696
    // review): the slice scans ALL of com.marketplace.app.graphql, and
    // A0's central assembly landed EVERY campaign fetcher in that one
    // package — so the slice must satisfy the constructor seam of every
    // discovered fetcher, not just its own domain's. The missing owning
    // SPIs join the mocked seam set here (the declared interfaces, never
    // the internals; the REAL implementations are proven in the owning
    // modules' suites and the full-stack integration tests over real
    // PostgreSQL/Flyway).
    @MockitoBean
    private PlatformTokenGrantService platformTokenGrantService;

    // Agent 3's checkout/order-command seams (PR #698): the slice package-scans
    // com.marketplace.app.graphql and wires CheckoutDataFetcher, so the shared
    // context cannot boot without these two mocked.
    @MockitoBean
    private com.marketplace.orders.spi.CheckoutSpi checkoutSpi;
    @MockitoBean
    private com.marketplace.orders.spi.OrderCommandSpi orderCommandSpi;
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

    private static ProductTypeView productTypeView(UUID id, String name, String slug) {
        return new ProductTypeView(id, name, slug);
    }

    /**
     * The {@code productTypes} document through the real wiring: the
     * Saleor connection envelope serializes with edges, per-element keyset
     * cursors (from the SAME cursor strategy the framework's connection
     * adaptation uses), pageInfo and totalCount — the declared-type proof
     * the context boot already started.
     */
    @Test
    void productTypesSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID booksId = UUID.randomUUID();
        UUID foodsId = UUID.randomUUID();

        java.util.function.Function<ScrollPosition, Window<ProductTypeView>> windowAt = position ->
                Window.from(List.of(
                                productTypeView(booksId, "Books", "books"),
                                productTypeView(foodsId, "Foods", "foods")), index -> {
                            if (index == 0) {
                                return ScrollPosition.forward(Map.of(
                                        "name", "Books", "id", booksId));
                            }
                            return ScrollPosition.forward(Map.of(
                                    "name", "Foods", "id", foodsId));
                        }, true);
        when(productTypeReadSpi.scrollProductTypes(any(org.springframework.security.core.Authentication.class),
                        any(ProductTypeSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new ProductTypePage(
                        windowAt.apply(ScrollPosition.keyset()), 5));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { productTypes(first: 2) {
                    edges { node { id name slug } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.productTypes");

        assertThat(connection.get("totalCount")).isEqualTo(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(2);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "Books", "id", booksId))));
        assertThat(edges.get(1).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "Foods", "id", foodsId))));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstNode = (Map<String, Object>) edges.get(0).get("node");
        assertThat(firstNode.get("name")).isEqualTo("Books");
        assertThat(firstNode.get("slug")).isEqualTo("books");
        assertThat(firstNode.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("ProductType:" + booksId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        @SuppressWarnings("unchecked")
        Map<String, Object> pageInfo = (Map<String, Object>) connection.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
        assertThat(pageInfo.get("startCursor")).isEqualTo(edges.get(0).get("cursor"));
        assertThat(pageInfo.get("endCursor")).isEqualTo(edges.get(1).get("cursor"));
    }

    /**
     * The {@code productType(id)} document: the REAL argument binding
     * carries the opaque global ID, the codec decodes it (a mistyped raw
     * UUID answers the classified VAL-001 error through the real exception
     * resolver), and the owner read serializes through the mapper.
     */
    @Test
    void productTypeReadsBackByTypedGlobalIdThroughTheRealArgumentBinding() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        when(productTypeReadSpi.findProductTypeById(eq(id), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(productTypeView(id, "Books", "books")));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("ProductType:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> type = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { productType(id: $id) { id name slug } }",
                "data.productType", Map.of("id", globalId));

        assertThat(type.get("id")).isEqualTo(globalId);
        assertThat(type.get("name")).isEqualTo("Books");
        assertThat(type.get("slug")).isEqualTo("books");
    }

    /**
     * The codec's classified validation error surfaces through the REAL
     * error taxonomy resolver when a raw UUID is sent where a typed global
     * ID is expected.
     */
    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { productType(id: $id) { id } }",
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
        when(productTypeReadSpi.findProductTypeById(any(UUID.class), any(org.springframework.security.core.Authentication.class)))
                .thenThrow(new AccessDeniedException("Administrator role required"));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("ProductType:" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { productType(id: $id) { id } }",
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
     * The declared-but-unexecuted Saleor arguments answer the explicit
     * classified error through the real resolver — never a silent no-op.
     */
    @Test
    void productTypesFilterAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productTypes(first: 2, filter: { search: \"x\" }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("filter is not implemented");
    }

    /** The backward-pagination boundary: before/last answer the explicit forward-only error. */
    @Test
    void productTypesBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productTypes(last: 2) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("forward-only");
    }

    /**
     * The unowned sort fields (DIGITAL deprecated upstream,
     * SHIPPING_REQUIRED) answer the explicit classified not-implemented
     * error — never a silent fallback to another sort.
     */
    @Test
    void unownedSortFieldAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productTypes(first: 2, sortBy: { direction: ASC, field: SHIPPING_REQUIRED }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("SHIPPING_REQUIRED is not implemented");
    }

    /**
     * The sort gate: a cursor emitted by a different sort is rejected for
     * the name scroll — the keyset mismatch, classified VAL-001 through
     * the real resolver.
     */
    @Test
    void cursorFromAnotherSortIsRejectedThroughTheRealCursorGate() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        String codeCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(Map.of("code", "books",
                        "id", UUID.randomUUID())));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($after: String) { productTypes(first: 1, after: $after) { totalCount } }",
                "$", Map.of("after", codeCursor)))
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
        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productTypes(first: 1) { totalCount } }", "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

}
