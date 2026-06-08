package com.marketplace.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SecurityProblemDetailIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void unauthenticatedApiRequestReturnsProblemDetail401() throws Exception {
        mockMvc.perform(get("/api/v1/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/unauthorized"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(jsonPath("$.instance").value("/api/v1/bookings"))
                .andExpect(jsonPath("$.errorCode").value("AUTHN-001"))
                .andExpect(jsonPath("$.category").value("authz"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void authenticatedNonAdminRequestReturnsProblemDetail403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/system"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://marketplace.com/errors/access-denied"))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("Access denied"))
                .andExpect(jsonPath("$.instance").value("/api/v1/admin/system"))
                .andExpect(jsonPath("$.errorCode").value("AUTHZ-001"))
                .andExpect(jsonPath("$.category").value("authz"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @Test
    void unauthorizedProblemDetailPayload_conformsToOpenApiProblemDetailSchema() throws Exception {
        String openApiDoc = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode openApiJson = objectMapper.readTree(openApiDoc);
        JsonNode schema = openApiJson.path("components").path("schemas").path("ProblemDetail");

        String body = mockMvc.perform(get("/api/v1/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode payload = objectMapper.readTree(body);

        assertThat(schema.path("properties").propertyNames()).contains("type", "title", "status", "detail", "instance");
        schema.path("required").forEach(requiredField -> assertThat(payload.hasNonNull(requiredField.asText())).isTrue());

        payload.propertyNames().forEach(fieldName ->
                assertThat(schema.path("properties").has(fieldName) || schema.path("additionalProperties").asBoolean())
                        .as("Field %s must be declared or allowed by extensions", fieldName)
                        .isTrue());
    }
}
