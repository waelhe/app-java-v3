package com.marketplace.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiConfigTest {

    @Test
    void marketplaceOpenApi_shouldExposeStandardErrorComponentsAndSecurity() {
        OpenApiConfig config = new OpenApiConfig();
        OpenAPI openAPI = config.marketplaceOpenAPI();

        assertNotNull(openAPI.getComponents());
        assertTrue(openAPI.getComponents().getSchemas().containsKey("ProblemDetail"));

        assertTrue(openAPI.getComponents().getResponses().containsKey("BadRequestResponse"));
        assertTrue(openAPI.getComponents().getResponses().containsKey("UnauthorizedResponse"));
        assertTrue(openAPI.getComponents().getResponses().containsKey("ForbiddenResponse"));
        assertTrue(openAPI.getComponents().getResponses().containsKey("NotFoundResponse"));
        assertTrue(openAPI.getComponents().getResponses().containsKey("ConflictResponse"));
        assertTrue(openAPI.getComponents().getResponses().containsKey("RateLimitedResponse"));
        assertTrue(openAPI.getComponents().getResponses().containsKey("InternalServerErrorResponse"));

        assertNotNull(openAPI.getComponents().getSecuritySchemes().get("bearerAuth"));
        assertFalse(openAPI.getSecurity().isEmpty());
    }
}
