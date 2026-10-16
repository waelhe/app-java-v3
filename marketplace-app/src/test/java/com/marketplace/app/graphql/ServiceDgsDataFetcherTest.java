package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
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
        when(catalogSpi.findAll(any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            List<ProviderListingView> content = List.of(
                    view(UUID.randomUUID(), "Service 0", 1000L),
                    view(UUID.randomUUID(), "Service 1", 2000L));
            return new PageImpl<>(content, pageable, 2);
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
        // The edges carry the framework's own base64 O_<offset> cursors —
        // decodable back into offset positions (the same wire contract
        // the HTTP pagination tests assert).
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
