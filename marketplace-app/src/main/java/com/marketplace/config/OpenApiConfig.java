package com.marketplace.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI marketplaceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Marketplace API")
                        .description("RESTful marketplace platform API with Spring Boot 4")
                        .version("v1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSchemas("ProblemDetail", problemDetailSchema())
                        .addResponses("BadRequestResponse", problemResponse("Bad request / validation error"))
                        .addResponses("NotFoundResponse", problemResponse("Resource not found"))
                        .addResponses("ConflictResponse", problemResponse("Conflict"))
                        .addResponses("UnauthorizedResponse", problemResponse("Authentication required"))
                        .addResponses("ForbiddenResponse", problemResponse("Access denied"))
                        .addResponses("TooManyRequestsResponse", problemResponse("Rate limit exceeded"))
                        .addResponses("InternalServerErrorResponse", problemResponse("Unexpected error"))
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }

    @Bean
    public OpenApiCustomizer defaultProblemResponsesCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(pathItem -> pathItem.readOperations().forEach(operation -> {
                addResponseIfMissing(operation, "400", "#/components/responses/BadRequestResponse");
                addResponseIfMissing(operation, "401", "#/components/responses/UnauthorizedResponse");
                addResponseIfMissing(operation, "403", "#/components/responses/ForbiddenResponse");
                addResponseIfMissing(operation, "404", "#/components/responses/NotFoundResponse");
                addResponseIfMissing(operation, "409", "#/components/responses/ConflictResponse");
                addResponseIfMissing(operation, "429", "#/components/responses/TooManyRequestsResponse");
                addResponseIfMissing(operation, "500", "#/components/responses/InternalServerErrorResponse");
            }));
        };
    }

    private static void addResponseIfMissing(io.swagger.v3.oas.models.Operation operation, String statusCode, String ref) {
        if (operation.getResponses() == null) {
            operation.setResponses(new io.swagger.v3.oas.models.responses.ApiResponses());
        }
        if (!operation.getResponses().containsKey(statusCode)) {
            operation.getResponses().addApiResponse(statusCode, new ApiResponse().$ref(ref));
        }
    }

    private static Schema<?> problemDetailSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("type", new StringSchema().example("https://marketplace.com/errors/not-found"));
        schema.addProperty("title", new StringSchema().example("Not Found"));
        schema.addProperty("status", new IntegerSchema().example(404));
        schema.addProperty("detail", new StringSchema().example("Resource not found"));
        schema.addProperty("instance", new StringSchema().example("/api/resource/123"));
        schema.addRequiredItem("title");
        schema.addRequiredItem("status");
        return schema;
    }

    private static ApiResponse problemResponse(String description) {
        return new ApiResponse().description(description).content(problemContent());
    }

    private static Content problemContent() {
        return new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<ProblemDetail>().$ref("#/components/schemas/ProblemDetail")));
    }
}
