package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "marketplace.graphql.errors.include-trace-id=true")
@ActiveProfiles("test")
class GraphQlErrorIntegrationTest {

    @LocalServerPort
    private int port;

    private HttpGraphQlTester graphQlTester;

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

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
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions()).containsEntry("errorCode", "NOT_FOUND");
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions()).containsEntry("category", "RESOURCE");
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions()).containsEntry("traceId", "trace-not-found");
                });
    }

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
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions()).containsEntry("errorCode", "DOMAIN_CONFLICT");
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions()).containsEntry("category", "DOMAIN");
                });
    }

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
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions()).containsEntry("errorCode", "INTERNAL_ERROR");
                    org.assertj.core.api.Assertions.assertThat(error.getExtensions()).containsEntry("category", "INTERNAL");
                });
    }
}
