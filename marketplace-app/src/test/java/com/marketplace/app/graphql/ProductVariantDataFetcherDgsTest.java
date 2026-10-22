package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductPage;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductVariantPage;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductVariantSort;
import com.marketplace.catalog.spi.ProductVariantView;
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

import java.time.Instant;
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
 * of the Saleor product-variant read roots — packet campaign-b5-a — the
 * house containerless pattern ({@code @SpringBootTest} over the GraphQL
 * adapter package slice + {@code @EnableDgsTest} +
 * {@link DgsQueryExecutor}): REAL GraphQL documents against the REAL
 * SDL and the REAL DGS wiring with no web layer and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the context
 * itself booting is the first proof — the hand-declared
 * {@code ProductVariantCountableConnection}/
 * {@code ProductVariantCountableEdge} and the bounded
 * {@code ProductVariant} type (Saleor's exact wire shape for the
 * declared closure, {@code totalCount} included, with its documented
 * platform extension fact {@code status} and the nullable-name
 * divergence) load through the DGS bridge. Then: the
 * {@code productVariants}/{@code productVariant} documents execute
 * through the real argument binding (including the
 * {@code ProductVariantSortingInput} sort input over BOTH pinned sort
 * fields), the typed global-ID codec, the mapper (including the
 * embedded {@code product} projection rendered through the SAME
 * Product wire shape the product roots serve) and the exception
 * taxonomy resolver; the connection envelope serializes with
 * per-element keyset cursors from the SAME cursor strategy the
 * framework adapters use. The owning catalog use case is mocked at the
 * declared seam — the full-stack proof (the real SQL keyset scroll and
 * the two-row-gated permission-aware population over the
 * Flyway-migrated schema) lives in
 * {@code ProductVariantGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = ProductVariantDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class ProductVariantDataFetcherDgsTest {

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
    // The category mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private CategoryWriteSpi categoryWriteSpi;

    @MockitoBean
    private ProductReadSpi productReadSpi;

    // The product-media mutation roots' owning seam (agent-2 N-slice) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.media.spi.ProductMediaWriteSpi productMediaWriteSpi;

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
    private DiscoverySpi discoverySpi;

    @MockitoBean
    private NeighborhoodFeedPort neighborhoodFeedPort;

    @MockitoBean
    private NeighborhoodPostDetailPort postDetailPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private IdentitySpi identitySpi;

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

    private static ProductView parentView(UUID id, String name) {
        return new ProductView(id, name, null, "ACTIVE");
    }

    private static ProductVariantView variantView(UUID id, String name, String sku, UUID parentId) {
        return new ProductVariantView(id, name, sku, "ACTIVE", parentView(parentId, "Widget"));
    }

    /**
     * The {@code productVariants} document through the real wiring: the
     * Saleor connection envelope serializes with edges, per-element
     * keyset cursors, pageInfo and totalCount — and the bounded
     * projection's typed facts (the global ID, the SKU, the status
     * extension fact, and the embedded parent product's own typed
     * facts) ride the same envelope.
     */
    @Test
    void productVariantsSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID variantId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(variantReadSpi.scrollVariants(any(org.springframework.security.core.Authentication.class),
                        any(ProductVariantSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new ProductVariantPage(
                        Window.from(List.of(variantView(variantId, "Large / Red", "b5a-sku-1", parentId)),
                                index -> ScrollPosition.forward(Map.of("id", variantId)),
                                true), 9));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { productVariants(first: 1) {
                    edges { node { id name sku status product { id name status } } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.productVariants");

        assertThat(connection.get("totalCount")).isEqualTo(9);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of("id", variantId))));
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) edges.get(0).get("node");
        assertThat(node.get("name")).isEqualTo("Large / Red");
        assertThat(node.get("sku")).isEqualTo("b5a-sku-1");
        assertThat(node.get("status")).isEqualTo("ACTIVE");
        assertThat(node.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("ProductVariant:" + variantId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        @SuppressWarnings("unchecked")
        Map<String, Object> parent = (Map<String, Object>) node.get("product");
        assertThat(parent.get("name")).isEqualTo("Widget");
        assertThat(parent.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Product:" + parentId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * The legacy free-form variant's honest null: a variant with no
     * display name answers name null — never a fabricated placeholder
     * (the registered nullable-name divergence).
     */
    @Test
    void namelessVariantAnswersTheHonestNullName() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID variantId = UUID.randomUUID();
        when(variantReadSpi.scrollVariants(any(org.springframework.security.core.Authentication.class),
                        any(ProductVariantSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new ProductVariantPage(
                        Window.from(List.of(variantView(variantId, null, "b5a-sku-2", UUID.randomUUID())),
                                index -> ScrollPosition.forward(Map.of("id", variantId)),
                                false), 1));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariants(first: 1) { edges { node { name sku } } } }",
                "data.productVariants");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) edges.get(0).get("node");
        assertThat(node.get("name")).isNull();
        assertThat(node.get("sku")).isEqualTo("b5a-sku-2");
    }

    /**
     * The {@code productVariant(id)} document: the REAL argument binding
     * carries the opaque global ID, the codec decodes it (a raw UUID
     * answers the classified VAL-001 error through the real exception
     * resolver), and the owner read serializes through the mapper.
     */
    @Test
    void productVariantReadsBackByTypedGlobalIdThroughTheRealArgumentBinding() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        UUID variantId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(variantReadSpi.findVariantById(eq(variantId),
                any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(variantView(variantId, "Small", "b5a-sku-3", parentId)));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("ProductVariant:" + variantId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> variant = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { productVariant(id: $id) { id name sku status product { id name } } }",
                "data.productVariant", Map.of("id", globalId));

        assertThat(variant.get("id")).isEqualTo(globalId);
        assertThat(variant.get("name")).isEqualTo("Small");
        assertThat(variant.get("sku")).isEqualTo("b5a-sku-3");
        assertThat(variant.get("status")).isEqualTo("ACTIVE");
        @SuppressWarnings("unchecked")
        Map<String, Object> parent = (Map<String, Object>) variant.get("product");
        assertThat(parent.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Product:" + parentId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * The sort mapping reaches the owning use case: LAST_MODIFIED_AT
     * with DESC maps onto the owner's executed keyset sort — and the
     * omitted sortBy maps onto the ID-ascending default — never a
     * silent fallback.
     */
    @Test
    void lastModifiedSortMappingReachesTheOwningUseCase() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        ArgumentCaptor<ProductVariantSort> sortCaptor = ArgumentCaptor.forClass(ProductVariantSort.class);
        when(variantReadSpi.scrollVariants(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new ProductVariantPage(
                        Window.from(List.<ProductVariantView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { productVariants(first: 2, sortBy: { direction: DESC, field: LAST_MODIFIED_AT }) { totalCount } }
                """,
                "data.productVariants");

        assertThat(sortCaptor.getValue())
                .isEqualTo(ProductVariantSort.of(ProductVariantSort.Field.LAST_MODIFIED_AT, false));
    }

    @Test
    void idSortMappingAndTheOmittedSortDefaultBothReachTheOwningUseCase() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        ArgumentCaptor<ProductVariantSort> sortCaptor = ArgumentCaptor.forClass(ProductVariantSort.class);
        when(variantReadSpi.scrollVariants(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new ProductVariantPage(
                        Window.from(List.<ProductVariantView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariants(first: 2, sortBy: { direction: ASC, field: ID }) { totalCount } }",
                "data.productVariants");
        assertThat(sortCaptor.getValue())
                .isEqualTo(ProductVariantSort.of(ProductVariantSort.Field.ID, true));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariants(first: 2) { totalCount } }", "data.productVariants");
        assertThat(sortCaptor.getValue())
                .isEqualTo(ProductVariantSort.of(ProductVariantSort.Field.ID, true));
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
                "query($id: ID!) { productVariant(id: $id) { id } }",
                "$", Map.of("id", UUID.randomUUID().toString())))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("Invalid global ID");
    }

    /**
     * The declared-but-unexecuted Saleor arguments answer the explicit
     * classified error through the real resolver — never a silent
     * no-op: the id-set filter, the deprecated filter, the where
     * filter, the free-text search, the channel scoping and the
     * backward pagination each name their own boundary.
     */
    @Test
    void productVariantsIdsAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariants(first: 2, ids: [\"b5a-id\"]) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("ids filtering is not implemented");
    }

    @Test
    void productVariantsFilterAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariants(first: 2, filter: { search: \"x\" }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("filter is not implemented");
    }

    @Test
    void productVariantsWhereAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariants(first: 2, where: { ids: [\"x\"] }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("where filter is not implemented");
    }

    @Test
    void productVariantsSearchAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariants(first: 2, search: \"red\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("search is not implemented");
    }

    @Test
    void productVariantsChannelAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariants(first: 2, channel: \"channel-1\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("channel filtering is not implemented");
    }

    @Test
    void productVariantsBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariants(last: 2) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("forward-only pagination");
    }

    /**
     * The ONE shared cursor gate: a cursor emitted by the OTHER sort
     * (the LAST_MODIFIED_AT keyset) is rejected by the ID-sort scroll —
     * the same VAL-001 taxonomy every malformed cursor answers.
     */
    @Test
    void cursorFromAnotherSortIsRejectedThroughTheRealCursorGate() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        String foreignCursor = cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                "updatedAt", Instant.parse("2026-10-21T10:00:00Z"),
                "id", UUID.randomUUID())));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($after: String) { productVariants(first: 1, after: $after) { totalCount } }",
                "$", Map.of("after", foreignCursor)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("after must be a forward keyset cursor")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "VAL-001"));
    }

    /**
     * The productVariant root's declared-but-unexecuted keys: sku (the
     * seller-namespaced SKU is not globally unique on this platform — a
     * bare global lookup is ambiguous), externalReference and channel
     * answer the explicit classified error whenever they are sent —
     * never silently ignored arguments.
     */
    @Test
    void productVariantSkuLookupAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($sku: String) { productVariant(sku: $sku) { id } }",
                "$", Map.of("sku", "b5a-sku-4")))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("sku lookup is not implemented");
    }

    @Test
    void productVariantExternalReferenceLookupAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($externalReference: String) { productVariant(externalReference: $externalReference) { id } }",
                "$", Map.of("externalReference", "erp-variant-1")))
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
    void keylessProductVariantLookupAnswersTheClassifiedValidationError() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariant { id } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("productVariant requires its id")
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
                "query { productVariants(first: 1) { totalCount } }", "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

    /**
     * The page-size contract rides the real properties binding: the
     * caller's {@code first} reaches the owning use case as the bounded
     * limit (clamped by the boundary's own cap — the same house contract
     * the category/product slices test).
     */
    @Test
    void requestedPageSizeReachesTheOwningUseCaseAsTheBoundedLimit() {
        authenticateAs("dgs-consumer-subject", "CONSUMER");
        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        when(variantReadSpi.scrollVariants(any(org.springframework.security.core.Authentication.class),
                        any(ProductVariantSort.class), any(), limitCaptor.capture()))
                .thenAnswer(invocation -> new ProductVariantPage(
                        Window.from(List.<ProductVariantView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { productVariants(first: 7) { totalCount } }", "data.productVariants");

        assertThat(limitCaptor.getValue().max()).isEqualTo(7);
        verify(variantReadSpi).scrollVariants(
                any(org.springframework.security.core.Authentication.class),
                any(ProductVariantSort.class), any(), any(Limit.class));
    }
}
