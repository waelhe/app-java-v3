package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogService;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the {@code createService} mutation contract through the
 * REAL schema, the REAL spring-graphql argument binder
 * ({@code GraphQlArgumentBinder} binds input keys to constructor parameter
 * names <em>by name</em>), the REAL Jakarta validation path
 * ({@code ValidationHelper} — @Valid on the controller parameter), and the
 * REAL exception taxonomy resolver — the exact layer that was broken since the
 * surface's origin (#226): the schema input field {@code price: Float!} never
 * matched the record component {@code priceCents}, so the mutation rejected
 * every price (paid and free) before this fix.
 *
 * <p>Aligned contract (B3 family — same price rule on every surface): the
 * input speaks integer cents ({@code priceCents: Int!}), exactly like the REST
 * surface ({@code CreateListingRequest.priceCents}), the DB check constraint
 * ({@code V2: price_cents >= 0}) and {@code BookingInfo} (rejects {@code < 0}
 * only). Zero is a valid free listing. The output keeps display units
 * ({@code Service.price} — major units via {@code ServiceMapper}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ServiceGraphQlMutationIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

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
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());

        graphQlTester = HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    private static ProviderListingView view(UUID id, String title, String description,
                                            String category, Long priceCents, String status) {
        return new ProviderListingView(id, title, description, category, priceCents, "SAR",
                UUID.randomUUID(), status, null, null);
    }

    private static Map<String, Object> input(String name, String description, Object priceCents) {
        Map<String, Object> input = new HashMap<>();
        input.put("name", name);
        input.put("description", description);
        input.put("category", "general");
        if (priceCents != null) {
            input.put("priceCents", priceCents);
        }
        return input;
    }

    /**
     * The mandatory zero-price mutation (deferred by CodeRabbit round 3 until
     * the binding fix was adopted — impossible to pass before it): a free
     * listing is a first-class domain outcome on every other surface, so the
     * repaired GraphQL surface must accept {@code priceCents: 0} and store 0.
     */
    @Test
    void createServiceWithZeroPriceSucceeds() {
        UUID listingId = UUID.randomUUID();
        ProviderListingView listing =
                view(listingId, "Free Consult", "Zero-priced listing", "general", 0L, "ACTIVE");
        when(catalogService.create(any(), eq("Free Consult"), eq("Zero-priced listing"),
                eq("general"), eq(0L), isNull()))
                .thenReturn(listing);

        graphQlTester.document("""
                        mutation($input: ServiceInput!) {
                            createService(input: $input) { id name category price currency status }
                        }
                        """)
                .variable("input", input("Free Consult", "Zero-priced listing", 0))
                .execute()
                .errors()
                .verify()
                .path("createService.id").entity(String.class).isEqualTo(listingId.toString())
                .path("createService.name").entity(String.class).isEqualTo("Free Consult")
                .path("createService.category").entity(String.class).isEqualTo("general")
                .path("createService.price").entity(Double.class).isEqualTo(0.0)
                .path("createService.currency").entity(String.class).isEqualTo("SAR")
                .path("createService.status").entity(String.class).isEqualTo("ACTIVE");

        verify(catalogService).create(any(), eq("Free Consult"), eq("Zero-priced listing"),
                eq("general"), eq(0L), isNull());
    }

    /** Positive prices bind to the record component by name and reach the catalog SPI unchanged. */
    @Test
    void createServiceWithPositivePriceSucceeds() {
        UUID listingId = UUID.randomUUID();
        ProviderListingView listing =
                view(listingId, "Deep Clean", "Paid listing", "general", 12345L, "ACTIVE");
        when(catalogService.create(any(), eq("Deep Clean"), eq("Paid listing"),
                eq("general"), eq(12345L), isNull()))
                .thenReturn(listing);

        graphQlTester.document("""
                        mutation($input: ServiceInput!) {
                            createService(input: $input) { name price }
                        }
                        """)
                .variable("input", input("Deep Clean", "Paid listing", 12345))
                .execute()
                .errors()
                .verify()
                .path("createService.name").entity(String.class).isEqualTo("Deep Clean")
                .path("createService.price").entity(Double.class).isEqualTo(123.45);

        verify(catalogService).create(any(), eq("Deep Clean"), eq("Paid listing"),
                eq("general"), eq(12345L), isNull());
    }

    /**
     * A negative price violates the cross-surface domain rule (V2 check
     * constraint, REST, BookingInfo) and must be rejected by the REAL Jakarta
     * validation path behind @Valid with the shared VALIDATION taxonomy —
     * the same semantics the REST GlobalExceptionHandler returns for
     * ConstraintViolationException.
     */
    @Test
    void createServiceWithNegativePriceIsRejected() {
        graphQlTester.document("""
                        mutation($input: ServiceInput!) {
                            createService(input: $input) { name }
                        }
                        """)
                .variable("input", input("Bad Price", "Negative price", -1))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VALIDATION_ERROR");
                    assertThat(error.getExtensions()).containsEntry("category", "VALIDATION");
                });

        verify(catalogService, never()).create(any(), anyString(), any(), anyString(), any(), any());
    }

    /**
     * {@code priceCents} is {@code Int!} (Non-Null) — the GraphQL spec
     * validates input coercion before execution begins, so a request omitting
     * it never reaches the handler (no write side effects).
     */
    @Test
    void createServiceMissingPriceIsRejected() {
        graphQlTester.document("""
                        mutation($input: ServiceInput!) {
                            createService(input: $input) { name }
                        }
                        """)
                .variable("input", input("No Price", "Missing price", null))
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());

        verify(catalogService, never()).create(any(), anyString(), any(), anyString(), any(), any());
    }
}
