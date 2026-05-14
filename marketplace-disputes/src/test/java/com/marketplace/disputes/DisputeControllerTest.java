package com.marketplace.disputes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DisputeControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final DisputeService service = mock(DisputeService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DisputeController(service))
            .setApiVersionStrategy(apiVersionStrategy())
            .build();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID disputeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "",
                        List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"))));
    }

    @Test
    void open() throws Exception {
        when(service.open(any(), any(), any())).thenReturn(mock(Dispute.class));

        mockMvc.perform(post("/api/v1/bookings/{bookingId}/disputes", bookingId)
                        .param("reason", "Not satisfied with service"))
                .andExpect(status().isOk());
    }

    @Test
    void list() throws Exception {
        when(service.listForBooking(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/bookings/{bookingId}/disputes", bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void resolve() throws Exception {
        when(service.resolve(any(), any())).thenReturn(mock(Dispute.class));

        mockMvc.perform(post("/api/v1/admin/disputes/{id}/resolve", disputeId))
                .andExpect(status().isOk());
    }
}
