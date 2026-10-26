package com.marketplace.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

class PublicApiSecurityCustomizerTest {

    private final PublicApiSecurityCustomizer customizer = new PublicApiSecurityCustomizer();

    private static OpenAPI spec(String method, String... paths) {
        Paths served = new Paths();
        for (String path : paths) {
            Operation operation = new Operation();
            PathItem item = new PathItem();
            switch (method) {
                case "GET" -> item.get(operation);
                case "POST" -> item.post(operation);
                case "PUT" -> item.put(operation);
                case "DELETE" -> item.delete(operation);
                case "PATCH" -> item.patch(operation);
                default -> throw new IllegalArgumentException(method);
            }
            served.addPathItem(path, item);
        }
        // The root-level global requirement the customizer must override per operation —
        // the exact shape OpenApiConfig.marketplaceOpenAPI() serves.
        return new OpenAPI()
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .paths(served);
    }

    @Test
    void marksThePublicReadSurfaces_withAnEmptySecurityList_overridingTheRootRequirement() {
        // The measured TestSprite cases (2026-10-25: the spec promised 401, the service
        // answered the honest 404 of a public surface) + every public read family.
        OpenAPI openAPI = spec("GET",
                "/api/v1/jobs", "/api/v1/jobs/{id}",
                "/api/v1/geo/{id}/children", "/api/v1/geo/tree",
                "/api/v1/providers/{id}/public", "/api/v1/users/{id}/public",
                "/api/v1/institutions", "/api/v1/institutions/{id}",
                "/api/v1/listings", "/api/v1/listings/{id}",
                "/api/v1/listings/category/{category}",
                "/api/v1/reviews/{id}", "/api/v1/reviews/provider/{providerId}",
                "/api/v1/search", "/api/v1/search/unified",
                "/api/v1/media/listings/{listingId}",
                "/api/v1/media/reviews/by-review/{reviewId}",
                "/api/v1/releases/latest",
                "/api/v1/store/products/{id}/questions",
                "/api/v1/store/sellers/{providerId}/summary",
                "/sitemap.xml", "/robots.txt");

        customizer.customise(openAPI);

        openAPI.getPaths().forEach((path, item) -> assertThat(item.getGet().getSecurity())
                .as("GET %s must be advertised public (security: [])", path)
                .isEmpty());
    }

    @Test
    void marksThePublicPostSurfaces_withAnEmptySecurityList() {
        // The public-POST family: account birth, the four token-redemption surfaces,
        // the lead submission, the ad click, and the signature-verified webhook ingress.
        OpenAPI openAPI = spec("POST",
                "/api/v1/auth/register",
                "/api/v1/auth/password-reset/request",
                "/api/v1/auth/password-reset/complete",
                "/api/v1/auth/email-verification/resend",
                "/api/v1/auth/email-verification/complete",
                "/api/v1/listings/{id}/leads",
                "/api/v1/ads/listings/{listingId}/clicks",
                "/api/v1/payments/webhooks/stripe",
                "/api/v1/payments/webhooks/{provider}");

        customizer.customise(openAPI);

        openAPI.getPaths().forEach((path, item) -> assertThat(item.getPost().getSecurity())
                .as("POST %s must be advertised public (security: [])", path)
                .isEmpty());
    }

    @Test
    void keepsTheRootRequirement_onTheAuthenticatedCarveOuts() {
        // The mirror of SecurityConfig's first-match-wins declaration order: the
        // carve-outs riding BEFORE their public blanket lines keep the inherited
        // bearerAuth — the load-bearing subtlety of the whole alignment.
        OpenAPI openAPI = spec("GET",
                "/api/v1/jobs/me", "/api/v1/jobs/me/published",
                "/api/v1/jobs/{id}/applications",
                "/api/v1/institutions/me",
                "/api/v1/listings/{id}/completeness");

        customizer.customise(openAPI);

        openAPI.getPaths().forEach((path, item) -> assertThat(item.getGet().getSecurity())
                .as("GET %s is an authenticated carve-out — the root requirement stays", path)
                .isNull());
    }

    @Test
    void keepsTheRootRequirement_onAuthenticatedAndAdminSurfaces() {
        OpenAPI gets = spec("GET", "/api/v1/orders/{id}", "/api/v1/admin/geo/{id}", "/api/v1/me/cart");
        customizer.customise(gets);
        gets.getPaths().forEach((path, item) -> assertThat(item.getGet().getSecurity())
                .as("GET %s stays on the root-level requirement (anyRequest().authenticated())", path)
                .isNull());

        OpenAPI posts = spec("POST", "/api/v1/listings", "/api/v1/admin/providers", "/api/v1/orders");
        customizer.customise(posts);
        posts.getPaths().forEach((path, item) -> assertThat(item.getPost().getSecurity())
                .as("POST %s stays on the root-level requirement", path)
                .isNull());
    }

    @Test
    void methodAgnosticRules_matchEveryOperationMethod() {
        // The webhook ingress rule carries no method constraint in SecurityConfig
        // (requestMatchers("/api/v1/payments/webhooks/**")) — the mirror must
        // advertise every webhook operation public, whatever its verb.
        OpenAPI openAPI = spec("GET", "/api/v1/payments/webhooks/{provider}");

        customizer.customise(openAPI);

        assertThat(openAPI.getPaths().get("/api/v1/payments/webhooks/{provider}").getGet().getSecurity()).isEmpty();
    }

    @Test
    void rootLevelRequirementItself_isUntouched() {
        OpenAPI openAPI = spec("GET", "/api/v1/jobs/{id}");

        customizer.customise(openAPI);

        // The per-operation override never mutates the document-level requirement —
        // every non-public operation keeps inheriting it.
        assertThat(openAPI.getSecurity()).hasSize(1);
        assertThat(openAPI.getSecurity().get(0).containsKey("bearerAuth")).isTrue();
    }

    @Test
    void nullPaths_isANoOp() {
        OpenAPI openAPI = new OpenAPI();

        customizer.customise(openAPI);

        assertThat(openAPI.getPaths()).isNull();
    }

    @Test
    void openApiConfig_exposesTheCustomizerAsABean() {
        // The bean method is the springdoc pickup point — the established
        // defaultProblemResponsesCustomizer pattern one method above it.
        OpenApiCustomizer bean = new OpenApiConfig().publicApiSecurityCustomizer();

        OpenAPI openAPI = spec("POST", "/api/v1/auth/register");
        bean.customise(openAPI);

        assertThat(openAPI.getPaths().get("/api/v1/auth/register").getPost().getSecurity()).isEmpty();
    }
}
