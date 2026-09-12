package com.marketplace.geo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L30 MVC slice: the public read surface and the negative authorization
 * contract of the admin surface (the acceptance criterion: public reads
 * anonymous, admin writes 403 for non-admins — service-level @PreAuthorize
 * is covered against the real service by the module's integration tests).
 */
@WebMvcTest(controllers = {GeoController.class, GeoAdminController.class},
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@Import(GeoControllerWebMvcTest.MethodSecurityConfig.class)
class GeoControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GeoService geoService;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    void tree_isPubliclyReadable() throws Exception {
        when(geoService.getTree()).thenReturn(
                new com.marketplace.shared.api.GeoLookupPort.GeoNode(
                        UUID.randomUUID(), null, 0, "سوريا", "Syria", "syria", List.of()));

        mockMvc.perform(get("/api/v1/geo/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameAr").value("سوريا"))
                .andExpect(jsonPath("$.slug").value("syria"));
    }

    @Test
    void suggest_belowThePrefixFloor_is400() throws Exception {
        mockMvc.perform(get("/api/v1/geo/suggest").param("q", "ق"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void adminCreate_asNonAdmin_is403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/geo")
                        .contentType("application/json")
                        .content("""
                                {"parentId": "%s", "nameAr": "حي", "slug": "new-neighborhood"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreate_anonymous_is401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/geo")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCreate_asAdmin_isAccepted() throws Exception {
        UUID parentId = UUID.randomUUID();
        when(geoService.createChild(parentId, "حي جديد", null, "new-neighborhood"))
                .thenReturn(new com.marketplace.shared.api.GeoLookupPort.GeoNode(
                        UUID.randomUUID(), parentId, 3, "حي جديد", null, "new-neighborhood"));

        mockMvc.perform(post("/api/v1/admin/geo")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("""
                                {"parentId": "%s", "nameAr": "حي جديد", "slug": "new-neighborhood"}
                                """.formatted(parentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("new-neighborhood"));
    }
}
