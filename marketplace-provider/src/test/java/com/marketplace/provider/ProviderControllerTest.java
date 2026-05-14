package com.marketplace.provider;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProviderControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final ProviderService service = mock(ProviderService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProviderController(service))
            .setApiVersionStrategy(apiVersionStrategy())
            .build();

    @Test
    void create() throws Exception {
        when(service.create(any(), any())).thenReturn(mock(ProviderProfile.class));

        mockMvc.perform(post("/api/v1/providers")
                        .contentType("application/json")
                        .content("{\"displayName\":\"Test Provider\",\"bio\":\"A bio\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void getById() throws Exception {
        when(service.getById(any())).thenReturn(mock(ProviderProfile.class));

        mockMvc.perform(get("/api/v1/providers/{id}", UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    @Test
    void update() throws Exception {
        when(service.update(any(), any(), any())).thenReturn(mock(ProviderProfile.class));

        mockMvc.perform(put("/api/v1/providers/{id}", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"displayName\":\"Updated Name\",\"bio\":\"Updated bio\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void verify() throws Exception {
        when(service.verify(any())).thenReturn(mock(ProviderProfile.class));

        mockMvc.perform(post("/api/v1/admin/providers/{id}/verify", UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    @Test
    void suspend() throws Exception {
        when(service.suspend(any())).thenReturn(mock(ProviderProfile.class));

        mockMvc.perform(post("/api/v1/admin/providers/{id}/suspend", UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    @Test
    void create_validationError() throws Exception {
        mockMvc.perform(post("/api/v1/providers")
                        .contentType("application/json")
                        .content("{\"displayName\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_validationError() throws Exception {
        mockMvc.perform(put("/api/v1/providers/{id}", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"displayName\": \"\"}"))
                .andExpect(status().isBadRequest());
    }
}
