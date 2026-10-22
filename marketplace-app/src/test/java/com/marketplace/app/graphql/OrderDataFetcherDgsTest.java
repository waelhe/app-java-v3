package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.community.spi.NeighborhoodFeedPort;
import com.marketplace.community.spi.NeighborhoodPostDetailPort;
import com.marketplace.discovery.spi.DiscoverySpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.orders.spi.OrderPage;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.orders.spi.OrderSort;
import com.marketplace.orders.spi.OrderView;
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
 * P4 (the canonical Saleor plan §5-P4): the DGS data-fetcher layer proof
 * of the Saleor order read roots — packet campaign-b5-b — the house
 * containerless pattern ({@code @SpringBootTest} over the GraphQL
 * adapter package slice + {@code @EnableDgsTest} +
 * {@link DgsQueryExecutor}): REAL GraphQL documents against the REAL
 * SDL and the REAL DGS wiring with no web layer and no containers.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the context
 * itself booting is the first proof — the hand-declared
 * {@code OrderCountableConnection}/{@code OrderCountableEdge}, the
 * bounded {@code Order} type and the VERBATIM Saleor
 * {@code OrderStatus}/{@code OrderSortField} enums load through the
 * DGS bridge. Then: the {@code orders}/{@code order} documents execute
 * through the real argument binding (including the
 * {@code OrderSortingInput} sort input over all three executed fields
 * and the deprecated CREATION_DATE alias), the typed global-ID codec,
 * the mapper (the lifecycle vocabulary's ADAPT mapping: PLACED renders
 * UNCONFIRMED, CONFIRMED renders UNFULFILLED, FULFILLED/CANCELLED
 * render their upstream spellings; the placement instant renders as
 * the ISO-8601 string the no-new-scalar house decision carries) and
 * the exception taxonomy resolver; the connection envelope serializes
 * with per-element keyset cursors from the SAME cursor strategy the
 * framework adapters use. The owning orders use case is mocked at the
 * declared seam — the full-stack proof (the REAL SQL keyset scroll AND
 * the ADMIN-only method-security enforcement with the AUTHZ-001
 * denial for every non-ADMIN caller over the Flyway-migrated schema)
 * lives in {@code OrderGraphQlIntegrationTest}.</p>
 */
@SpringBootTest(classes = OrderDataFetcherDgsTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class OrderDataFetcherDgsTest {

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

    private static OrderView orderView(UUID id, Instant placedAt, String status) {
        return new OrderView(id, placedAt, status);
    }

    /**
     * The {@code orders} document through the real wiring: the Saleor
     * connection envelope serializes with edges, per-element keyset
     * cursors, pageInfo and totalCount — and the bounded projection's
     * typed facts (the global ID, the ISO-8601 placement instant, and
     * the lifecycle ADAPT mapping rendering the platform's PLACED as
     * upstream's UNCONFIRMED) ride the same envelope.
     */
    @Test
    void ordersSerializesTheSaleorConnectionEnvelopeWithKeysetCursors() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID orderId = UUID.randomUUID();
        Instant placedAt = Instant.parse("2026-10-20T09:30:00Z");
        when(orderReadSpi.scrollOrders(any(org.springframework.security.core.Authentication.class),
                        any(OrderSort.class), any(), any(Limit.class)))
                .thenAnswer(invocation -> new OrderPage(
                        Window.from(List.of(orderView(orderId, placedAt, "PLACED")),
                                index -> ScrollPosition.forward(Map.of(
                                        "placedAt", placedAt, "id", orderId)),
                                true), 9));

        Map<String, Object> connection = dgsQueryExecutor.executeAndExtractJsonPath(
                """
                query { orders(first: 1) {
                    edges { node { id created status } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                    totalCount
                } }
                """,
                "data.orders");

        assertThat(connection.get("totalCount")).isEqualTo(9);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) connection.get("edges");
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).get("cursor"))
                .isEqualTo(cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                        "placedAt", placedAt, "id", orderId))));
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) edges.get(0).get("node");
        assertThat(node.get("id")).isEqualTo(java.util.Base64.getEncoder()
                .encodeToString(("Order:" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(node.get("created")).isEqualTo("2026-10-20T09:30:00Z");
        // The lifecycle ADAPT mapping: the platform's PLACED renders
        // upstream's UNCONFIRMED.
        assertThat(node.get("status")).isEqualTo("UNCONFIRMED");
    }

    /** Every platform state renders its own upstream vocabulary value. */
    @Test
    void theLifecycleAdaptMappingRendersEveryPlatformState() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID id = UUID.randomUUID();
        Instant placedAt = Instant.parse("2026-10-20T10:00:00Z");

        for (String[] pair : new String[][]{
                {"PLACED", "UNCONFIRMED"}, {"CONFIRMED", "UNFULFILLED"},
                {"FULFILLED", "FULFILLED"}, {"CANCELLED", "CANCELED"}}) {
            when(orderReadSpi.findOrderById(eq(id),
                    any(org.springframework.security.core.Authentication.class)))
                    .thenReturn(java.util.Optional.of(orderView(id, placedAt, pair[0])));

            String globalId = java.util.Base64.getEncoder()
                    .encodeToString(("Order:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String, Object> order = dgsQueryExecutor.executeAndExtractJsonPath(
                    "query($id: ID!) { order(id: $id) { status } }",
                    "data.order", Map.of("id", globalId));
            assertThat(order.get("status")).as("platform %s", pair[0]).isEqualTo(pair[1]);
        }
    }

    /**
     * The {@code order(id)} document: the REAL argument binding carries
     * the opaque global ID, the codec decodes it (a raw UUID answers
     * the classified VAL-001 error through the real exception
     * resolver), and the owner read serializes through the mapper.
     */
    @Test
    void orderReadsBackByTypedGlobalIdThroughTheRealArgumentBinding() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        UUID orderId = UUID.randomUUID();
        Instant placedAt = Instant.parse("2026-10-19T08:15:00Z");
        when(orderReadSpi.findOrderById(eq(orderId),
                any(org.springframework.security.core.Authentication.class)))
                .thenReturn(java.util.Optional.of(orderView(orderId, placedAt, "CONFIRMED")));

        String globalId = java.util.Base64.getEncoder()
                .encodeToString(("Order:" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> order = dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { order(id: $id) { id created status } }",
                "data.order", Map.of("id", globalId));

        assertThat(order.get("id")).isEqualTo(globalId);
        assertThat(order.get("created")).isEqualTo("2026-10-19T08:15:00Z");
        assertThat(order.get("status")).isEqualTo("UNFULFILLED");
    }

    /**
     * The sort mapping reaches the owning use case: CREATED_AT with ASC
     * maps onto the owner's executed keyset sort; the deprecated
     * CREATION_DATE alias rides the SAME executed sort (exactly
     * upstream's own deprecation mapping); the omitted sortBy maps onto
     * the CREATED_AT-descending default (the newest order first) —
     * never a silent fallback.
     */
    @Test
    void sortMappingAndTheDeprecatedAliasReachTheOwningUseCase() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        ArgumentCaptor<OrderSort> sortCaptor = ArgumentCaptor.forClass(OrderSort.class);
        when(orderReadSpi.scrollOrders(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new OrderPage(
                        Window.from(List.<OrderView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(first: 2, sortBy: { direction: ASC, field: CREATED_AT }) { totalCount } }",
                "data.orders");
        assertThat(sortCaptor.getValue()).isEqualTo(OrderSort.of(OrderSort.Field.CREATED_AT, true));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(first: 2, sortBy: { direction: DESC, field: CREATION_DATE }) { totalCount } }",
                "data.orders");
        assertThat(sortCaptor.getValue()).isEqualTo(OrderSort.of(OrderSort.Field.CREATED_AT, false));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(first: 2) { totalCount } }", "data.orders");
        assertThat(sortCaptor.getValue()).isEqualTo(OrderSort.of(OrderSort.Field.CREATED_AT, false));
    }

    @Test
    void statusAndLastModifiedSortsReachTheOwningUseCase() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        ArgumentCaptor<OrderSort> sortCaptor = ArgumentCaptor.forClass(OrderSort.class);
        when(orderReadSpi.scrollOrders(any(org.springframework.security.core.Authentication.class),
                        sortCaptor.capture(), any(), any(Limit.class)))
                .thenAnswer(invocation -> new OrderPage(
                        Window.from(List.<OrderView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(first: 2, sortBy: { direction: ASC, field: STATUS }) { totalCount } }",
                "data.orders");
        assertThat(sortCaptor.getValue()).isEqualTo(OrderSort.of(OrderSort.Field.STATUS, true));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(first: 2, sortBy: { direction: ASC, field: LAST_MODIFIED_AT }) { totalCount } }",
                "data.orders");
        assertThat(sortCaptor.getValue()).isEqualTo(OrderSort.of(OrderSort.Field.LAST_MODIFIED_AT, true));
    }

    /**
     * The codec's classified validation error surfaces through the REAL
     * error taxonomy resolver when a raw UUID is sent where a typed
     * global ID is expected.
     */
    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($id: ID!) { order(id: $id) { id } }",
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
    void ordersFilterAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(first: 2, filter: { search: \"x\" }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("filter is not implemented");
    }

    @Test
    void ordersWhereAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(first: 2, where: { ids: [\"x\"] }) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("where filter is not implemented");
    }

    @Test
    void ordersSearchAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(first: 2, search: \"widget\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("search is not implemented");
    }

    @Test
    void ordersChannelAnswersTheExplicitNotImplementedErrorThroughTheRealResolver() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(first: 2, channel: \"channel-1\") { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("channel filtering is not implemented");
    }

    @Test
    void ordersBackwardPaginationAnswersTheExplicitForwardOnlyError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(last: 2) { totalCount } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("forward-only pagination");
    }

    /** The five unowned sort fields each answer their own explicit error. */
    @Test
    void unownedSortFieldsAnswerTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        for (String field : new String[]{"NUMBER", "RANK", "CUSTOMER", "PAYMENT", "FULFILLMENT_STATUS"}) {
            assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                    "query { orders(first: 2, sortBy: { direction: ASC, field: " + field + " }) { totalCount } }",
                    "$"))
                    .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                    .hasMessageContaining("sort field " + field + " is not implemented");
        }
    }

    /**
     * The ONE shared cursor gate: a cursor emitted by the OTHER sort
     * (the STATUS keyset) is rejected by the CREATED_AT scroll — the
     * same VAL-001 taxonomy every malformed cursor answers.
     */
    @Test
    void cursorFromAnotherSortIsRejectedThroughTheRealCursorGate() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        String foreignCursor = cursorStrategy.toCursor(ScrollPosition.forward(Map.of(
                "status", "PLACED", "id", UUID.randomUUID())));

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($after: String) { orders(first: 1, after: $after) { totalCount } }",
                "$", Map.of("after", foreignCursor)))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("after must be a forward keyset cursor")
                .satisfies(ex -> assertThat(
                                ((com.netflix.graphql.dgs.exceptions.QueryException) ex).getErrors().get(0)
                                        .getExtensions())
                        .containsEntry("errorCode", "VAL-001"));
    }

    /** The externalReference argument answers the explicit classified error — never silently ignored. */
    @Test
    void orderExternalReferenceLookupAnswersTheExplicitNotImplementedError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query($externalReference: String) { order(externalReference: $externalReference) { id } }",
                "$", Map.of("externalReference", "erp-order-1")))
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
    void keylessOrderLookupAnswersTheClassifiedValidationError() {
        authenticateAs("dgs-admin-subject", "ADMIN");

        assertThatThrownBy(() -> dgsQueryExecutor.executeAndExtractJsonPath(
                "query { order { id } }",
                "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("order requires its id")
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
                "query { orders(first: 1) { totalCount } }", "$"))
                .isInstanceOf(com.netflix.graphql.dgs.exceptions.QueryException.class)
                .hasMessageContaining("An unexpected error occurred");
    }

    /**
     * The page-size contract rides the real properties binding: the
     * caller's {@code first} reaches the owning use case as the bounded
     * limit (clamped by the boundary's own cap — the same house contract
     * the category/product/variant slices test).
     */
    @Test
    void requestedPageSizeReachesTheOwningUseCaseAsTheBoundedLimit() {
        authenticateAs("dgs-admin-subject", "ADMIN");
        ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
        when(orderReadSpi.scrollOrders(any(org.springframework.security.core.Authentication.class),
                        any(OrderSort.class), any(), limitCaptor.capture()))
                .thenAnswer(invocation -> new OrderPage(
                        Window.from(List.<OrderView>of(), index -> ScrollPosition.keyset(), false), 0));

        dgsQueryExecutor.executeAndExtractJsonPath(
                "query { orders(first: 7) { totalCount } }", "data.orders");

        assertThat(limitCaptor.getValue().max()).isEqualTo(7);
        verify(orderReadSpi).scrollOrders(
                any(org.springframework.security.core.Authentication.class),
                any(OrderSort.class), any(), any(Limit.class));
    }
}
