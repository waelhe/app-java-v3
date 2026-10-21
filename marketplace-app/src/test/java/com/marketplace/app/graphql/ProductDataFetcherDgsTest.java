package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.CmsNavigationPort;
import com.marketplace.shared.api.CmsPagePort;
import com.marketplace.identity.spi.UserManagementSpi;
import com.marketplace.identity.spi.CustomerTypeSpi;
import com.marketplace.identity.spi.AddressManagementSpi;
import com.marketplace.identity.spi.AccountLifecycleSpi;
import com.marketplace.pricing.spi.DiscountReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductPage;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductSort;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductView;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2): the DGS data-fetcher layer proof
 * of the Saleor product read roots — packet campaign-b4-c — the house
 * containerless pattern ({@code @SpringBootTest} over the GraphQL
 * adapter package slice + {@code @EnableDgsTest} +
 * {@link DgsQueryExecutor}): REAL GraphQL documents against the REAL
 * SDL and the REAL DGS wiring with no web layer and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the context
 * itself booting is the first proof — the hand-declared
 * {@code ProductCountableConnection}/{@code ProductCountableEdge} and
 * the bounded {@code Product} type (Saleor's exact wire shape for the
 * declared closure, {@code totalCount} included, with its documented
 * platform extension fact {@code status}) load through the DGS bridge.
 * Then: the {@code products}/{@code product} documents execute through
 * the real argument binding (including the {@code ProductOrder} sort
 * input), the typed global-ID codec, the mapper (including the
 * productType reference's typed global ID) and the exception taxonomy
 * resolver; the connection envelope serializes with per-element keyset
 * cursors from the SAME cursor strategy the framework adapters use.
 * The owning catalog use case is mocked at the declared seam — the
 * full-stack proof (the real SQL keyset scroll and the
 * permission-aware population over the Flyway-migrated schema) lives
 * in {@code ProductGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = ProductDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class ProductDataFetcherDgsTest {

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
    // the AttributeDataFetcherDgsTest precedent.
    @MockitoBean
    private CatalogSpi catalogSpi;

    @MockitoBean
    private ProductAdminSpi productAdminSpi;

    @MockitoBean
    private ProductTypeReadSpi productTypeReadSpi;

    @MockitoBean
    private AttributeReadSpi attributeReadSpi;

    @MockitoBean
    private CategoryReadSpi categoryReadSpi;

    @MockitoBean
    private ProductReadSpi productReadSpi;

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

    @MockitoBean
    private OrderReadSpi orderReadSpi;


    @MockitoBean
    private DiscountReadSpi discountReadSpi;

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

    private static ProductView productView(UUID id, String name, UUID productTypeId, String status) {
        return new ProductView(id, name, productTypeId, status);
    }

    /**
     * The {@code products} document through the real wiring: the Saleor
     * connection envelope serializes with edges, per-element keyset
     * cursors, pageInfo and totalCount — and the bounded projection's
     * typed facts (the global ID, the display name, the productType
     * reference's typed global ID and the status extension fact) ride
     * the same envelope.
     */
    @Test
    void productsSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID widgetId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();

        java.util.function.Function<ScrollPosition, Window<ProductView>> windowAt = position ->
                Window.from(List.of(productView(widgetId, "Widget", typeId, "ACTIVE")), index ->
                                ScrollPosition.forward(Map.of("name", "Widget", "id", widgetId)),
                        true);
        when(productReadSpi.scrollProducts(any(org.springframework.security.core.Authentication.class),
                        any(ProductSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new ProductPage(
                        windowAt.apply(ScrollPosition.keyset()), 9));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { products(first: 1) {
                    edges { node { id name productType status } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.products");

        assertThat(connection.get("totalCount")).isEqualTo(9);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "name", "Widget", "id", widgetId))));
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) edges.get(0).get("node");
        assertThat(node.get("name")).isEqualTo("Widget");
        assertThat(node.get("status")).isEqualTo("ACTIVE");
        assertThat(node.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Product:" + widgetId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(node.get("productType")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("ProductType:" + typeId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * The schema-less legacy product's honest null: a product with no
     * reusable goods schema answers productType null — never a
     * fabricated reference.
     */
    @Test
    void schemalessProductAnswersTheHonestNullProductType() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID id = UUID.randomUUID();
        when(productReadSpi.scrollProducts(any(org.springframework.security.core.Authentication.class),
                        any(ProductSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new ProductPage(
                        Window.from(List.of(productView(id, "Legacy Widget", null, "ACTIVE")), index ->
                                        ScrollPosition.forward(Map.of("name", "Legacy Widget", "id", id)),
                                false), 1));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                "query { products(first: 1) { edges { node { productType } } } }", "data.products");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges.get(0).get("productType")).isNull();
    }

    /**
     * The {@code product(id)} document: the REAL argument binding
     * carries the opaque global ID, the codec decodes it (a raw UUID
     * answers the classified VAL-001 error through the real exception
     * resolver), and the owner read serializes through the mapper.
     */
    @Test
    void productReadsBackByTypedGlobalIdThroughTheRealArgumentBinding() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID id = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        when(productReadSpi.findProductById(eq(id), any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(productView(id, "Widget", typeId, "ACTIVE")));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> product = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { product(id: $id) { id name productType status } }",
                "data.product", Map.of("id", globalId));

        assertThat(product.get("id")).isEqualTo(globalId);
        assertThat(product.get("name")).isEqualTo("Widget");
        assertThat(product.get("status")).isEqualTo("ACTIVE");
        assertThat(product.get("productType")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("ProductType:" + typeId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * The sort mapping reaches the owning use case: NAME with DESC maps
     * onto the owner's executed keyset sort — and the omitted sortBy
     * maps onto the NAME-ascending default — never a silent fallback.
     */
    @Test
    void nameSortMappingReachesTheOwningUseCase() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        ArgumentCaptor<ProductSort> sortCaptor = ArgumentCaptor.forClass(ProductSort.class);
        when(productReadSpi.scrollProducts(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new ProductPage(
                        Window.from(List.<ProductView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { products(first: 2, sortBy: { direction: DESC, field: NAME }) { totalCount } }
                """,
                "data.products");

        assertThat(sortCaptor.getValue()).isEqualTo(ProductSort.of(ProductSort.Field.NAME, false));
    }

    @Test
    void omittedSortMapsOntoTheNameAscendingDefault() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        ArgumentCaptor<ProductSort> sortCaptor = ArgumentCaptor.forClass(ProductSort.class);
        when(productReadSpi.scrollProducts(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new ProductPage(
                        Window.from(List.<ProductView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { products(first: 2) { totalCount } }", "data.products");

        assertThat(sortCaptor.getValue()).isEqualTo(ProductSort.of(ProductSort.Field.NAME, true));
    }

    /**
     * The codec's classified validation error surfaces through the REAL
     * error taxonomy resolver when a raw UUID is sent where a typed
     * global ID is expected.
     */
    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { product(id: $id) { id } }",
                "$", Map.of("id", UUID.randomUUID().toString())))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
    }

    /**
     * The declared-but-unexecuted Saleor arguments answer the explicit
     * classified error through the real resolver — never a silent
     * no-op: the deprecated filter, the where filter, the free-text
     * search, the channel scoping and the backward pagination each
     * name their own boundary.
     */
    @Test
    void productsFilterAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { products(first: 2, filter: { search: \"x\" }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("filter is not implemented");
    }

    @Test
    void productsWhereAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { products(first: 2, where: { ids: [\"x\"] }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("where filter is not implemented");
    }

    @Test
    void productsSearchAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { products(first: 2, search: \"widget\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("search is not implemented");
    }

    @Test
    void productsChannelAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { products(first: 2, channel: \"channel-1\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("channel filtering is not implemented");
    }

    @Test
    void productsBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { products(last: 2) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("forward-only");
    }

    /**
     * The unowned sort legs: the attributeId sort and the thirteen
     * unowned ProductOrderField values answer the explicit classified
     * not-implemented error — never a silent fallback to another sort.
     */
    @Test
    void attributeSortAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        String attributeId = java.util.Base64.getEncoder()
                .encodeToString(("Attribute:" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($attributeId: ID) { products(first: 2, sortBy: { direction: ASC, attributeId: $attributeId }) { totalCount } }",
                "$", Map.of("attributeId", attributeId)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("attribute sort is not implemented");
    }

    @Test
    void unownedSortFieldAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { products(first: 2, sortBy: { direction: ASC, field: PRICE }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("PRICE is not implemented");
    }

    /**
     * The sort gate: a cursor emitted by ANOTHER vocabulary (a
     * slug-keyed cursor on the default name scroll) is rejected — the
     * keyset mismatch, classified VAL-001 through the real resolver.
     */
    @Test
    void cursorFromAnotherSortIsRejectedThroughTheRealCursorGate() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        String slugCursor = cursorStrategy.toCursor(
                ScrollPosition.forward(Map.of("slug", "widget",
                        "id", UUID.randomUUID())));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($after: String) { products(first: 1, after: $after) { totalCount } }",
                "$", Map.of("after", slugCursor)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("after must be a forward keyset cursor")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "VAL-001"));
    }

    /**
     * The product root's declared-but-unexecuted keys: slug,
     * slugLanguageCode, externalReference and channel answer the
     * explicit classified error whenever they are sent — never
     * silently ignored arguments.
     */
    @Test
    void productSlugLookupAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($slug: String) { product(slug: $slug) { id } }",
                "$", Map.of("slug", "widget")))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("slug lookup is not implemented");
    }

    @Test
    void productExternalReferenceLookupAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($externalReference: String) { product(externalReference: $externalReference) { id } }",
                "$", Map.of("externalReference", "erp-widget-1")))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("externalReference lookup is not implemented");
    }

    /**
     * The keyless call shape: no id answers the explicit classified
     * validation error — a keyless lookup is a client error, never a
     * fabricated absence (the recorded divergence from upstream's null
     * answer).
     */
    @Test
    void keylessProductLookupAnswersTheClassifiedValidationError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { product { id } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("product requires its id")
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
                "query { products(first: 1) { totalCount } }", "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

    /**
     * The page-size contract rides the real properties binding: the
     * caller's {@code first} reaches the owning use case as the bounded
     * limit (clamped by the boundary's own cap — the same house contract
     * the category slice tests).
     */
    @Test
    void requestedPageSizeReachesTheOwningUseCaseAsTheBoundedLimit() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        when(productReadSpi.scrollProducts(any(org.springframework.security.core.Authentication.class),
                        any(ProductSort.class), any(), limitCaptor.capture()))
                .thenAnswer(invocation -> new ProductPage(
                        Window.from(List.<ProductView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { products(first: 7) { totalCount } }", "data.products");

        assertThat(limitCaptor.getValue().max()).isEqualTo(7);
        verify(productReadSpi).scrollProducts(
                any(org.springframework.security.core.Authentication.class),
                any(ProductSort.class), any(), any(Limit.class));
    }
}
