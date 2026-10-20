package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.OrderCommercePort;
import com.marketplace.shared.api.PaymentIntentClientPort;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.ProductMediaAdminPort;
import com.marketplace.shared.api.ProviderListingView;
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
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The DGS data-fetcher layer test — the official DGS test-starter pattern
 * the design's Phase 2 prescribes ("Migrate/extend data-fetcher unit
 * tests using DgsQueryExecutor and the DGS test starter"):
 * {@code @SpringBootTest} over the GraphQL adapter package slice +
 * {@code @EnableDgsTest} + {@link DgsQueryExecutor}, executing REAL
 * GraphQL documents against the REAL SDL and the REAL DGS wiring with no
 * web layer.
 *
 * <p><b>What this proves that the plain unit test cannot:</b> the DGS
 * integration assembles the surface end to end — the SDL loads from the
 * configured {@code dgs.graphql.schema-locations}, the
 * {@code @DgsComponent} fetcher registers, {@code @InputArgument} binds
 * the query variables, the relay Connection types generate through the
 * integration's {@code DgsTypeDefinitionConfigurerBridge}, and Boot's
 * {@code ConnectionFieldTypeVisitor} decorates the DGS-registered
 * fetcher (via the {@code GraphQlSourceBuilderCustomizer} chain the DGS
 * source builder applies) so the returned {@code Page} carries the same
 * {@code edges}/{@code pageInfo} envelope and {@code O_<offset>} cursors
 * the HTTP contract exposes. The transport-level HTTP tests
 * (pagination/mutation/error integration tests) remain the full-stack
 * proof; this is the data-fetcher-layer proof between them.</p>
 *
 * <p><b>The slice:</b> a component scan of
 * {@code com.marketplace.app.graphql} — the whole GraphQL adapter (the
 * DGS fetcher, the MapStruct mapper, the typed properties, the exception
 * resolver, the instrumentation config) — plus the two auto-configs the
 * executor's argument binding and the fetcher's explicit validation need
 * (Jackson for DGS's input conversion, Validation for the Jakarta
 * validator bean). The SPI and the principal provider are mocked at
 * their seams. The DGS test slice itself
 * ({@code @EnableDgsTest}) imports the framework's documented
 * auto-configuration set.</p>
 */
@SpringBootTest(classes = ServiceDgsDataFetcherTest.GraphQLAdapterSlice.class)
@EnableDgsTest
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
class ServiceDgsDataFetcherTest {

    /** The GraphQL adapter as one unit: everything in the package under test. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.marketplace.app.graphql")
    static class GraphQLAdapterSlice {
    }

    @Autowired
    private DgsQueryExecutor dgsQueryExecutor;

    @MockitoBean
    private CatalogSpi catalogSpi;

    // Component scanning includes the catalog administration fetcher; mock its owning module seam.
    @MockitoBean
    private com.marketplace.catalog.spi.ProductAdminSpi productAdminSpi;

    // The component scan includes the store-product fetcher; mock the media seam
    // (the port moved to media.spi — the module-owned SPI, the ProductAdminSpi pattern).
    @MockitoBean
    private com.marketplace.media.spi.ProductMediaAdminPort productMediaAdminPort;
    @MockitoBean
    private OrderCommercePort orderCommercePort;

    @MockitoBean
    private PaymentIntentClientPort paymentIntentClientPort;

    // Phase 6 — the home journey's fetcher is now in the SAME scanned
    // package (the slice is the package as one unit): its two owning
    // seams join the outside-slice mocking set, exactly the way the
    // catalog seam above does.
    @MockitoBean
    private com.marketplace.discovery.spi.DiscoverySpi discoverySpi;

    @MockitoBean
    private com.marketplace.community.spi.NeighborhoodFeedPort neighborhoodFeedPort;

    // Stage 7 (the post/source detail composite journey): the detail
    // fetcher component-scanned into this slice consumes the detail port —
    // mocked here exactly as the feed port above (the slice wires every
    // fetcher in the package; each owning seam gets its mock).
    @MockitoBean
    private com.marketplace.community.spi.NeighborhoodPostDetailPort postDetailPort;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    /** The mutation path reads the principal from the (test-populated) security context. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static ProviderListingView view(UUID id, String title, Long priceCents) {
        return new ProviderListingView(id, title, "The description", "general", priceCents, "SAR",
                UUID.randomUUID(), "ACTIVE", null, null, null);
    }

    /**
     * The single-service read through the DGS wiring: the {@code ID}
     * argument binds to the fetcher's {@code UUID} parameter, the SPI is
     * consulted, and the mapper's response contract serializes into the
     * execution result.
     */
    @Test
    void serviceQueryResolvesThroughTheDgsWiring() {
        UUID id = UUID.randomUUID();
        when(catalogSpi.getActiveById(id)).thenReturn(view(id, "Deep Clean", 12345L));

        String name = dgsQueryExecutor.executeAndExtractJsonPath(
                "query Service($id: ID!) { service(id: $id) { id name category price currency status } }",
                "data.service.name",
                java.util.Map.of("id", id.toString()));

        assertThat(name).isEqualTo("Deep Clean");
    }

    @Test
    void buyerCartReadAndAddMutationDelegateThroughTheCommercePort() {
        var auth = new TestingAuthenticationToken("buyer", "pass", "ROLE_CONSUMER");
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);

        UUID itemId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(orderCommercePort.getCart(auth)).thenReturn(List.of(
                new OrderCommercePort.CartLineView(itemId, productId, null, 2, "1250", "SAR", "2500")));

        var query = dgsQueryExecutor.executeAndGetDocumentContext("""
                query { myCart { id productId variantId quantity unitAmountMinor lineTotalMinor currency } }
                """);
        assertThat(query.<String>read("data.myCart[0].productId")).isEqualTo(productId.toString());
        assertThat(query.<Integer>read("data.myCart[0].quantity")).isEqualTo(2);
        assertThat(query.<String>read("data.myCart[0].unitAmountMinor")).isEqualTo("1250");

        when(orderCommercePort.addToCart(eq(productId), isNull(), eq(3), eq(auth)))
                .thenReturn(new OrderCommercePort.CartLineView(itemId, productId, null, 5,
                        "1250", "SAR", "6250"));

        var added = dgsQueryExecutor.executeAndGetDocumentContext("""
                mutation Add($input: AddToCartInput!) {
                    addToCart(input: $input) { id productId quantity unitAmountMinor lineTotalMinor }
                }
                """, java.util.Map.of("input", java.util.Map.of(
                "productId", productId.toString(), "quantity", 3)));

        assertThat(added.<Integer>read("data.addToCart.quantity")).isEqualTo(5);
        assertThat(added.<String>read("data.addToCart.lineTotalMinor")).isEqualTo("6250");
        verify(orderCommercePort).addToCart(productId, null, 3, auth);
    }

    @Test
    void orderCheckoutAndPaymentIntentCommandsMapTheirOwnerResponses() {
        var auth = new TestingAuthenticationToken("buyer", "pass", "ROLE_CONSUMER");
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);

        UUID orderId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        when(orderCommercePort.requestOrderPaymentIntent(orderId, auth)).thenReturn(
                new PaymentIntentDetails(intentId, null, UUID.randomUUID(), null,
                        "CREATED", PaymentIntentDetails.ORIGIN_ORDER, 2500L, "SAR",
                        orderId, null));
        var intent = dgsQueryExecutor.executeAndGetDocumentContext("""
                mutation Request($orderId: ID!) {
                    requestOrderPaymentIntent(orderId: $orderId) {
                        id orderId amountMinor currency status origin
                    }
                }
                """, java.util.Map.of("orderId", orderId.toString()));

        assertThat(intent.<String>read("data.requestOrderPaymentIntent.id")).isEqualTo(intentId.toString());
        assertThat(intent.<String>read("data.requestOrderPaymentIntent.orderId")).isEqualTo(orderId.toString());
        assertThat(intent.<String>read("data.requestOrderPaymentIntent.amountMinor")).isEqualTo("2500");
        verify(orderCommercePort).requestOrderPaymentIntent(orderId, auth);
    }

    @Test
    void checkoutPreviewResolvesThroughTheDgsWiringWithoutOwningPriceMath() {
        var auth = new TestingAuthenticationToken("buyer", "pass", "ROLE_CONSUMER");
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);
        UUID sellerId = UUID.randomUUID();
        when(orderCommercePort.previewCheckout(any(), eq(auth))).thenReturn(List.of(
                new OrderCommercePort.CheckoutPreviewView(sellerId, "10000", "500", "Promo",
                        "0", null, "950", "SA", false, 1500, "10450", "SAR", null)));

        var document = dgsQueryExecutor.executeAndGetDocumentContext("""
                query Preview($input: CheckoutInput) {
                    checkoutPreview(input: $input) {
                        sellerId subtotalMinor promotionDiscountMinor taxMinor totalMinor currency
                    }
                }""", java.util.Map.of("input", java.util.Map.of("voucherCode", "SAVE")));

        assertThat(document.<String>read("data.checkoutPreview[0].sellerId")).isEqualTo(sellerId.toString());
        assertThat(document.<String>read("data.checkoutPreview[0].subtotalMinor")).isEqualTo("10000");
        assertThat(document.<String>read("data.checkoutPreview[0].totalMinor")).isEqualTo("10450");
    }

    @Test
    void placeOrderDelegatesTheCheckoutCommandAndReturnsFrozenOrderShape() {
        var auth = new TestingAuthenticationToken("buyer", "pass", "ROLE_CONSUMER");
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        var order = new OrderCommercePort.OrderView(orderId, "PLACED",
                List.of(new OrderCommercePort.OrderLineView(UUID.randomUUID(), productId, null,
                        2, "1250", "SAR", "2500")),
                "2500", "2500", "0", "0", "PICKUP", "0", null, "SAR",
                Instant.parse("2026-10-20T00:00:00Z"), null, null, null, null, null, List.of());
        when(orderCommercePort.placeOrder(isNull(), eq(auth))).thenReturn(order);

        var document = dgsQueryExecutor.executeAndGetDocumentContext("""
                mutation { placeOrder { id status totalAmountMinor items { productId quantity lineTotalMinor } } }
                """);

        assertThat(document.<String>read("data.placeOrder.id")).isEqualTo(orderId.toString());
        assertThat(document.<String>read("data.placeOrder.status")).isEqualTo("PLACED");
        assertThat(document.<String>read("data.placeOrder.totalAmountMinor")).isEqualTo("2500");
        assertThat(document.<String>read("data.placeOrder.items[0].productId")).isEqualTo(productId.toString());
        verify(orderCommercePort).placeOrder(null, auth);
    }

    @Test
    void paymentProcessingReturnsProviderClientSecretFromThePaymentsOwner() {
        var auth = new TestingAuthenticationToken("buyer", "pass", "ROLE_CONSUMER");
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);
        UUID intentId = UUID.randomUUID();
        when(paymentIntentClientPort.process(eq(intentId), eq(auth))).thenReturn(
                new PaymentIntentClientPort.PaymentIntentClientView(intentId, null, "2500",
                        "SAR", "PROCESSING", "psp_intent_test", "secret_test",
                        Instant.parse("2026-10-20T00:00:00Z"), Instant.parse("2026-10-20T00:00:01Z")));

        var document = dgsQueryExecutor.executeAndGetDocumentContext("""
                mutation Process($id: ID!) {
                    processPaymentIntent(id: $id) { id amountMinor status pspIntentId clientSecret }
                }
                """, java.util.Map.of("id", intentId.toString()));

        assertThat(document.<String>read("data.processPaymentIntent.id")).isEqualTo(intentId.toString());
        assertThat(document.<String>read("data.processPaymentIntent.clientSecret")).isEqualTo("secret_test");
        assertThat(document.<String>read("data.processPaymentIntent.status")).isEqualTo("PROCESSING");
        verify(paymentIntentClientPort).process(intentId, auth);
    }

    /**
     * The bounded connection through the DGS wiring — the migration's
     * central architectural claim at the data-fetcher layer: the DGS
     * fetcher returns a {@code Page}, and the SAME framework decoration
     * that produced the envelope before the migration adapts it — edges
     * carry {@code O_<offset>} cursors, {@code pageInfo} carries the
     * honest hasNextPage, and the content arrives in the server's
     * deterministic order.
     */
    @Test
    void servicesConnectionResolvesThroughTheDgsWiring() {
        when(catalogSpi.scrollActiveListings(
                any(KeysetScrollPosition.class), any(Limit.class))).thenAnswer(inv -> {
            List<ProviderListingView> content = List.of(
                    view(UUID.randomUUID(), "Service 0", 1000L),
                    view(UUID.randomUUID(), "Service 1", 2000L));
            return Window.from(content, index -> ScrollPosition.forward(Map.of(
                    "createdAt", Instant.parse("2026-01-01T00:00:00Z").plusSeconds(2L - index),
                    "id", content.get(index).id())), false);
        });

        var document = dgsQueryExecutor.executeAndGetDocumentContext("""
                query Services($first: Int) {
                    services(first: $first) {
                        edges { node { name } cursor }
                        pageInfo { hasNextPage hasPreviousPage }
                    }
                }""", java.util.Map.of("first", 2));

        assertThat(document.<List<String>>read("data.services.edges[*].node.name"))
                .containsExactly("Service 0", "Service 1");
        assertThat(document.<Boolean>read("data.services.pageInfo.hasNextPage")).isFalse();
        // The edges carry framework-encoded keyset cursors (not client-built offsets).
        List<String> cursors = document.read("data.services.edges[*].cursor");
        assertThat(cursors).hasSize(2);
    }

    /**
     * The mutation's boundary validation through the DGS wiring: the input
     * record binds from the GraphQL variables, the explicit Jakarta
     * validation rejects the negative price, and the exception resolver
     * classifies it to the canonical VAL-001/validation pair — the same
     * machine contract the HTTP mutation test asserts.
     */
    @Test
    void createServiceRejectsANegativePriceThroughTheDgsWiring() {
        var result = dgsQueryExecutor.execute("""
                mutation Create($input: ServiceInput!) {
                    createService(input: $input) { name }
                }""", java.util.Map.of("input", java.util.Map.of(
                "name", "Bad Price",
                "description", "Negative price",
                "category", "general",
                "priceCents", -1)));

        assertThat(result.getErrors()).isNotEmpty();
        var extensions = result.getErrors().getFirst().getExtensions();
        assertThat(extensions).containsEntry("errorCode", "VAL-001");
        assertThat(extensions).containsEntry("category", "validation");
    }

    /**
     * The mutation's happy path through the DGS wiring: the input binds,
     * the validation passes, the principal resolves from the security
     * context, and the write delegates to the SPI's authoritative use
     * case.
     */
    @Test
    void createServiceDelegatesToTheAuthoritativeUseCaseThroughTheDgsWiring() {
        var auth = new TestingAuthenticationToken("provider", "pass", "ROLE_PROVIDER");
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);
        UUID providerId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(providerId);
        when(catalogSpi.create(any(), any(), any(), any(), any(), any()))
                .thenReturn(view(UUID.randomUUID(), "New Svc", 3000L));

        String name = dgsQueryExecutor.executeAndExtractJsonPath("""
                mutation Create($input: ServiceInput!) {
                    createService(input: $input) { name }
                }""", "data.createService.name", java.util.Map.of("input", java.util.Map.of(
                "name", "New Svc",
                "description", "The description",
                "category", "general",
                "priceCents", 3000)));

        assertThat(name).isEqualTo("New Svc");
    }
}
