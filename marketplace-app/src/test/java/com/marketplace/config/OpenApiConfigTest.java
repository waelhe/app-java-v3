package com.marketplace.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void customizer_addsDefaultProblemResponses_whenMissing() {
        OpenAPI openAPI = new OpenAPI().paths(new Paths().addPathItem("/api/items", new PathItem().get(new Operation())));

        config.defaultProblemResponsesCustomizer().customise(openAPI);

        Operation operation = openAPI.getPaths().get("/api/items").getGet();
        assertThat(operation.getResponses()).containsKeys("400", "401", "403", "404", "409", "429", "500");
        assertThat(operation.getResponses().get("404").get$ref()).isEqualTo("#/components/responses/NotFoundResponse");
    }

    @Test
    void customizer_doesNotOverrideExistingResponses() {
        Operation operation = new Operation();
        operation.setResponses(new io.swagger.v3.oas.models.responses.ApiResponses());
        operation.getResponses().addApiResponse("404", new io.swagger.v3.oas.models.responses.ApiResponse().description("Custom"));
        OpenAPI openAPI = new OpenAPI().paths(new Paths().addPathItem("/api/custom", new PathItem().get(operation)));

        config.defaultProblemResponsesCustomizer().customise(openAPI);

        assertThat(operation.getResponses().get("404").getDescription()).isEqualTo("Custom");
    }
}
