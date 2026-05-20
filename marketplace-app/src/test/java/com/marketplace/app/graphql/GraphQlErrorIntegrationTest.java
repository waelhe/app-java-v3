package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogService;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.mockito.Mockito.when;

@SpringBootTest(properties = "marketplace.graphql.errors.include-trace-id=true")
@AutoConfigureHttpGraphQlTester
@ActiveProfiles("test")
class GraphQlErrorIntegrationTest {

    @Autowired
    private HttpGraphQlTester graphQlTester;

    @MockitoBean
    private CatalogService catalogService;

    @Test
    void shouldMapNotFoundError() {
        UUID id = UUID.randomUUID();
        when(catalogService.getById(id)).thenThrow(new ResourceNotFoundException("Listing", id));

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
        when(catalogService.getById(id)).thenThrow(new IllegalStateException("Listing status conflict"));

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
        when(catalogService.getById(id)).thenThrow(new RuntimeException("sensitive details should not leak"));

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
