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
                        .addSchemas("ProblemDetail", new ObjectSchema()
                                .addProperty("type", new StringSchema().example("https://marketplace.com/errors/not-found"))
                                .addProperty("title", new StringSchema().example("Not Found"))
                                .addProperty("status", new IntegerSchema().example(404))
                                .addProperty("detail", new StringSchema().example("Resource not found"))
                                .addProperty("instance", new StringSchema().example("/api/resource/123")))
                        .addResponses("BadRequestResponse", new ApiResponse()
                                .description("Bad request / validation error")
                                .content(problemContent()))
                        .addResponses("NotFoundResponse", new ApiResponse()
                                .description("Resource not found")
                                .content(problemContent()))
                        .addResponses("ConflictResponse", new ApiResponse()
                                .description("Conflict")
                                .content(problemContent()))
                        .addResponses("UnauthorizedResponse", new ApiResponse()
                                .description("Authentication required")
                                .content(problemContent()))
                        .addResponses("ForbiddenResponse", new ApiResponse()
                                .description("Access denied")
                                .content(problemContent()))
                        .addResponses("TooManyRequestsResponse", new ApiResponse()
                                .description("Rate limit exceeded")
                                .content(problemContent()))
                        .addResponses("InternalServerErrorResponse", new ApiResponse()
                                .description("Unexpected error")
                                .content(problemContent()))
                        .addSecuritySchemes("bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }

    private static Content problemContent() {
        return new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<ProblemDetail>().$ref("#/components/schemas/ProblemDetail")));
    }
}
