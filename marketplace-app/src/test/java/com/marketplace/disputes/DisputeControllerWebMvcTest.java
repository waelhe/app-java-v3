package com.marketplace.disputes;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = DisputeController.class,
    excludeAutoConfiguration = {
        OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
    })
class DisputeControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DisputeService service;

    @MockitoBean
    private DisputeMapper disputeMapper;

    @Test
    @WithMockUser
    void open_returnsOk() throws Exception {
        UUID bookingId = UUID.randomUUID();
        var dispute = mockDispute();
        var response = mockResponse();

        when(service.open(any(), any(), any())).thenReturn(dispute);
        when(disputeMapper.toResponse(dispute)).thenReturn(response);

        mockMvc.perform(post("/api/v1/bookings/{bookingId}/disputes", bookingId)
                        .param("reason", "Not as described"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void list_returnsOk() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(service.listForBooking(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/bookings/{bookingId}/disputes", bookingId))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void resolve_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var dispute = mockDispute();
        var response = mockResponse();

        when(service.resolve(any(), any())).thenReturn(dispute);
        when(disputeMapper.toResponse(dispute)).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/disputes/{id}/resolve", id))
                .andExpect(status().isOk());
    }

    private static Dispute mockDispute() {
        return org.mockito.Mockito.mock(Dispute.class);
    }

    private static DisputeResponse mockResponse() {
        return new DisputeResponse(UUID.randomUUID(), null, null, null, null, null, null);
    }
}
