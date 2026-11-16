package com.marketplace.realestate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L31 MVC slice: the property surface's request validation and
 * authorization shape. Service-level ownership (403) and the ACTIVE gate
 * (404) are covered against the real service by the module integration
 * test; this slice pins the HTTP contract.
 */
@WebMvcTest(controllers = PropertyController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@Import(PropertyControllerWebMvcTest.MethodSecurityConfig.class)
class PropertyControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RealestateService realestateService;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    void publicGet_isReadableAnonymously() throws Exception {
        UUID listingId = UUID.randomUUID();
        when(realestateService.getPublicByListingId(listingId))
                .thenReturn(new com.marketplace.shared.api.PropertyDetailsPort.PropertyView(
                        listingId, com.marketplace.shared.api.PropertyPurpose.RENT,
                        com.marketplace.shared.api.PropertyType.APARTMENT,
                        120, 3, 2, 1, 5, 2015, false,
                        java.util.List.of("elevator"), null, null, null, null));

        mockMvc.perform(get("/api/v1/listings/{id}/property", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId").value(listingId.toString()))
                .andExpect(jsonPath("$.purpose").value("RENT"))
                .andExpect(jsonPath("$.propertyType").value("APARTMENT"))
                .andExpect(jsonPath("$.areaM2").value(120))
                .andExpect(jsonPath("$.amenities[0]").value("elevator"));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void put_withValidBody_isAccepted() throws Exception {
        UUID listingId = UUID.randomUUID();
        when(realestateService.upsert(eq(listingId), any(), any()))
                .thenReturn(new com.marketplace.shared.api.PropertyDetailsPort.PropertyView(
                        listingId, com.marketplace.shared.api.PropertyPurpose.RENT,
                        com.marketplace.shared.api.PropertyType.APARTMENT,
                        120, 3, 2, 1, 5, null, null, null, null, null, null, null));

        mockMvc.perform(put("/api/v1/listings/{id}/property", listingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("RENT"));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void put_withMissingEnumPair_is400() throws Exception {
        mockMvc.perform(put("/api/v1/listings/{id}/property", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"areaM2": 120}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void put_withNonPositiveArea_is400() throws Exception {
        mockMvc.perform(put("/api/v1/listings/{id}/property", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purpose": "RENT", "propertyType": "APARTMENT", "areaM2": 0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void put_withInvalidEnumValue_is400() throws Exception {
        mockMvc.perform(put("/api/v1/listings/{id}/property", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"purpose": "LEASE", "propertyType": "APARTMENT"}
                                """))
                .andExpect(status().isBadRequest());
    }

    private static String validBody() {
        return """
                {"purpose": "RENT", "propertyType": "APARTMENT", "areaM2": 120,
                 "rooms": 3, "bathrooms": 2, "floorNumber": 1, "totalFloors": 5,
                 "amenities": ["elevator"]}
                """;
    }
}
