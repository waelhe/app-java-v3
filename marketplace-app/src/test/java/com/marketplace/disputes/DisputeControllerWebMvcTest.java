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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = DisputeController.class,
    excludeAutoConfiguration = {
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

        when(service.resolve(any(), any(), any())).thenReturn(dispute);
        when(disputeMapper.toResponse(dispute)).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/disputes/{id}/resolve", id)
                        .contentType("application/json")
                        .content("{\"resolution\": \"REFUND_CONSUMER\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void resolve_withNullResolution_isRejected() throws Exception {
        // L24: the decision is required — @NotNull fires
        // MethodArgumentNotValidException through the house taxonomy (the
        // same boundary-test class as the L21 reply tests). Note: a body with
        // an UNKNOWN enum value currently lands in the catch-all 500 (an
        // HttpMessageNotReadableException taxonomy gap shared by every
        // @RequestBody endpoint) — fixing that is a shared-layer decision,
        // deliberately out of this layer's scope.
        mockMvc.perform(post("/api/v1/admin/disputes/{id}/resolve", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"resolution\": null}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private static Dispute mockDispute() {
        return org.mockito.Mockito.mock(Dispute.class);
    }

    private static DisputeResponse mockResponse() {
        return new DisputeResponse(UUID.randomUUID(), null, null, null, null, null, null, null, null, null);
    }
}
