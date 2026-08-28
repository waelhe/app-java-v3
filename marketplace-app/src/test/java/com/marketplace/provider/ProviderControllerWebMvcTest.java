package com.marketplace.provider;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = ProviderController.class,
    excludeAutoConfiguration = {
        OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
    })
class ProviderControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProviderService providerService;

    @MockitoBean
    private ProviderMapper providerMapper;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    void getById_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var profile = mockProviderProfile();
        var response = mockResponse();

        when(providerService.getById(id)).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        mockMvc.perform(get("/api/v1/providers/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void create_returnsOk() throws Exception {
        var profile = mockProviderProfile();
        var response = mockResponse();

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(providerService.create(any(), any(), any())).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        mockMvc.perform(post("/api/v1/providers")
                        .contentType("application/json")
                        .content("""
                                {"displayName": "Test Provider", "bio": "A test provider"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void verify_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var profile = mockProviderProfile();
        var response = mockResponse();

        when(providerService.verify(any())).thenReturn(profile);
        when(providerMapper.toResponse(profile)).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/providers/{id}/verify", id))
                .andExpect(status().isOk());
    }

    private static com.marketplace.provider.ProviderProfile mockProviderProfile() {
        return org.mockito.Mockito.mock(com.marketplace.provider.ProviderProfile.class);
    }

    private static ProviderResponse mockResponse() {
        return new ProviderResponse(UUID.randomUUID(), null, null, null, null, null);
    }
}
