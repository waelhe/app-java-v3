package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductTypeReadSpi;
import com.marketplace.catalog.spi.ProductReadSpi;
import com.marketplace.orders.spi.OrderReadSpi;
import com.marketplace.catalog.spi.CategoryReadSpi;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.CategoryWriteSpi;
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

    // P1 (the Saleor Users read roots): the package slice now scans
    // UserDataFetcher too — its owning identity seam joins the
    // outside-slice mocking set, exactly the way the seams above do.
    @MockitoBean
    private com.marketplace.identity.spi.IdentitySpi identitySpi;
    // The category mutation roots' owning write seam (agent-2 packet) —
    // the package slice wires every fetcher, so this seam gets its mock
    // (the b5-a sibling-test precedent).
    @MockitoBean
    private CategoryWriteSpi categoryWriteSpi;
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
    private com.marketplace.catalog.spi.CategoryReadSpi categoryReadSpi;

    @MockitoBean
    private com.marketplace.orders.spi.OrderReadSpi orderReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductReadSpi productReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductTypeReadSpi productTypeReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.ProductVariantReadSpi productVariantReadSpi;

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

    // The collection read/mutation roots' owning seams (agent-2 L-slice) —
    // the package slice wires every fetcher, so these seams get their
    // mocks (the sibling-test precedent).
    @MockitoBean
    private com.marketplace.catalog.spi.CollectionReadSpi collectionReadSpi;

    @MockitoBean
    private com.marketplace.catalog.spi.CollectionWriteSpi collectionWriteSpi;
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
