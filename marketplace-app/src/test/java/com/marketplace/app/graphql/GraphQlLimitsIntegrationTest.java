package com.marketplace.app.graphql;

import java.util.List;
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
import test.config.IntegrationContainers;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The query-cost budget of the GraphQL surface over the REAL execution
 * pipeline (design §5.7/§6: verified depth/complexity limits under the
 * exact resolved graphql-java 25.0): the aliased-fan-out abuse shape —
 * many parallel copies of the heaviest field selection — multiplies the
 * weighted complexity and must be aborted by graphql-java's own
 * {@code MaxQueryComplexityInstrumentation} with its own
 * {@code ExecutionAborted} classification (measured framework behavior,
 * pinned here; NOT custom-normalized through the resolver — the design
 * plan's §7 rule: test what the exact version returns first).
 *
 * <p>The same class pins the REQUEST-level error layer the resolver never
 * sees (design §7: "Test two distinct layers"): a syntax error, an unknown
 * field, and an invalid argument coercion — each surfaces the framework's
 * own validation-error envelope, distinct from the data-fetcher taxonomy,
 * proving the two layers are not conflated.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "marketplace.graphql.limits.max-query-complexity=60")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class GraphQlLimitsIntegrationTest {

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

    @MockitoBean
    private com.marketplace.catalog.CatalogService catalogService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private HttpGraphQlTester graphQlTester;

    @BeforeEach
    void setUpGraphQlTester() {
        Jwt testJwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", "test-user")
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of("PROVIDER"))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(testJwt);

        when(catalogService.findAll(any(Pageable.class))).thenAnswer(inv -> {
            Pageable pageable = inv.getArgument(0);
            return new PageImpl<>(List.of(), pageable, 0);
        });

        graphQlTester = HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    /**
     * The single-selection baseline executes: the complexity budget (60 in
     * this context) is calibrated to admit the ordinary full-selection
     * read (~14 by the documented default calculator
     * {@code 1 + childComplexity}).
     */
    @Test
    void anOrdinaryFullSelectionExecutesWithinTheBudget() {
        graphQlTester.document("""
                        query {
                            services(first: 3) {
                                edges { node { id name description category price currency status } cursor }
                                pageInfo { hasPreviousPage hasNextPage startCursor endCursor }
                            }
                        }
                        """)
                .execute()
                .errors().verify()
                .path("services.edges").entityList(Object.class).hasSize(0);
    }

    /**
     * The aliased fan-out is aborted: enough parallel copies of the heavy
     * selection to blow the 60-unit budget — the framework's own
     * ExecutionAborted error, with the message carrying the measured
     * complexity, and NO data (execution aborted before any fetcher ran).
     */
    @Test
    void theAliasedFanOutIsAbortedByTheComplexityBudget() {
        // ~14 complexity per selection; 5 aliases ≈ 70 > 60.
        String aliased = """
                query {
                    p1: services(first: 3) { edges { node { id name description category price currency status } cursor } pageInfo { hasNextPage endCursor } }
                    p2: services(first: 3) { edges { node { id name description category price currency status } cursor } pageInfo { hasNextPage endCursor } }
                    p3: services(first: 3) { edges { node { id name description category price currency status } cursor } pageInfo { hasNextPage endCursor } }
                    p4: services(first: 3) { edges { node { id name description category price currency status } cursor } pageInfo { hasNextPage endCursor } }
                    p5: services(first: 3) { edges { node { id name description category price currency status } cursor } pageInfo { hasNextPage endCursor } }
                }
                """;

        graphQlTester.document(aliased)
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    var error = errors.getFirst();
                    // The framework's own abort envelope: measured message +
                    // ExecutionAborted classification — not the resolver's
                    // taxonomy (request-level budget rejection).
                    assertThat(error.getMessage())
                            .contains("maximum query complexity exceeded");
                    assertThat(error.getExtensions())
                            .containsEntry("classification", "ExecutionAborted");
                });
    }

    /**
     * REQUEST-level layer (never reaches a data fetcher, so the resolver
     * taxonomy does not apply): a syntax error surfaces the framework's
     * own invalid-syntax envelope (graphql-java's InvalidSyntaxError —
     * classification "InvalidSyntax" on the wire, per
     * GraphqlErrorHelper's documented classification move).
     */
    @Test
    void aSyntaxErrorIsTheFrameworkRequestLevelEnvelope() {
        graphQlTester.document("query { services(first: ")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).containsIgnoringCase("invalid");
                    assertThat(error.getExtensions())
                            .containsEntry("classification", "InvalidSyntax");
                });
    }

    /**
     * REQUEST-level layer: an unknown field is a schema-validation error
     * with the framework's ValidationError envelope — classification
     * "ValidationError" on the wire (the WrongType/FieldUndefined detail
     * rides the message), not the resolver taxonomy.
     */
    @Test
    void anUnknownFieldIsTheFrameworkValidationError() {
        graphQlTester.document("query { services { doesNotExist } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("doesNotExist");
                    assertThat(error.getExtensions())
                            .containsEntry("classification", "ValidationError");
                });
    }

    /**
     * REQUEST-level layer: an invalid argument coercion (after: 42 — an
     * Int where the schema declares String) is rejected before execution
     * with the framework's ValidationError envelope (the WrongType detail
     * rides the message) — the read path never runs.
     */
    @Test
    void anInvalidArgumentCoercionIsRejectedBeforeExecution() {
        graphQlTester.document("query { services(after: 42) { edges { node { id } } } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("after");
                    assertThat(error.getExtensions())
                            .containsEntry("classification", "ValidationError");
                });
    }
}
