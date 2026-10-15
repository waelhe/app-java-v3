package com.marketplace.app.graphql;

import test.config.IntegrationContainers;

import com.marketplace.catalog.CatalogService;
import com.marketplace.shared.api.ApiErrorTaxonomy;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "marketplace.graphql.errors.include-trace-id=true")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class GraphQlErrorIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @LocalServerPort
    private int port;

    private HttpGraphQlTester graphQlTester;

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /**
     * Boots the real HTTP transport against the test containers with an
     * authenticated PROVIDER Jwt — the same stateless security chain the
     * production endpoint runs, so every envelope test below exercises the
     * integrated stack rather than the resolver in isolation.
     */
    @BeforeEach
    void setUpGraphQlTester() {
        Jwt testJwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", "test-user")
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of("PROVIDER"))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(testJwt);

        graphQlTester = HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    /**
     * A not-found read maps to the canonical NOT_FOUND pair (NF-001 /
     * RESOURCE) with the domain's designated-safe message — and, with the
     * trace-id policy enabled for the test profile, the correlation id
     * rides the extensions exactly as the contract documents.
     */
    @Test
    void shouldMapNotFoundError() {
        UUID id = UUID.randomUUID();
        when(catalogService.getActiveById(id)).thenThrow(new ResourceNotFoundException("Listing", id));

        graphQlTester.mutate()
                .header("X-Correlation-ID", "trace-not-found")
                .build()
                .document("query($id: ID!){ service(id: $id) { id name } }")
                .variable("id", id.toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    org.assertj.core.api.Assertions.assertThat(error.getMessage()).contains("Listing not found");
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions())
                            .containsEntry("errorCode", ApiErrorTaxonomy.NOT_FOUND.errorCode());
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions())
                            .containsEntry("category", ApiErrorTaxonomy.NOT_FOUND.category());
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions()).containsEntry("traceId", "trace-not-found");
                });
    }

    /**
     * A domain conflict (IllegalStateException from the use case) maps to
     * the canonical CONFLICT pair with the domain message — the same
     * semantics the REST ProblemDetail surface emits for the same
     * exception.
     */
    @Test
    void shouldMapDomainConflictError() {
        UUID id = UUID.randomUUID();
        when(catalogService.getActiveById(id)).thenThrow(new IllegalStateException("Listing status conflict"));

        graphQlTester.document("query($id: ID!){ service(id: $id) { id name } }")
                .variable("id", id.toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    org.assertj.core.api.Assertions.assertThat(error.getMessage()).isEqualTo("Listing status conflict");
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions())
                            .containsEntry("errorCode", ApiErrorTaxonomy.CONFLICT.errorCode());
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions())
                            .containsEntry("category", ApiErrorTaxonomy.CONFLICT.category());
                });
    }

    /**
     * An unknown failure is masked: the generic internal sentence and the
     * canonical INTERNAL pair — the exception's own message, stack trace,
     * and any persistence detail never reach the client.
     */
    @Test
    void shouldHideInternalErrorDetails() {
        UUID id = UUID.randomUUID();
        when(catalogService.getActiveById(id)).thenThrow(new RuntimeException("sensitive details should not leak"));

        graphQlTester.document("query($id: ID!){ service(id: $id) { id name } }")
                .variable("id", id.toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    org.assertj.core.api.Assertions.assertThat(error.getMessage()).isEqualTo("An unexpected error occurred");
                    org.assertj.core.api.Assertions.assertThat(error.getMessage()).doesNotContain("sensitive");
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions())
                            .containsEntry("errorCode", ApiErrorTaxonomy.INTERNAL.errorCode());
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions())
                            .containsEntry("category", ApiErrorTaxonomy.INTERNAL.category());
                });
    }
}
