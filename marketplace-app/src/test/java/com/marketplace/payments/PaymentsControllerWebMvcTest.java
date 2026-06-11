package com.marketplace.payments;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentsController.class)
class PaymentsControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentsService paymentsService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private PaymentMapper paymentMapper;

    @MockitoBean
    private PaymentIntentMapper paymentIntentMapper;

    @Test
    void getIntent_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var intent = mockPaymentIntent(id);
        var response = mockIntentResponse(id);

        when(paymentsService.getIntentForUser(any(), any())).thenReturn(intent);
        when(paymentIntentMapper.toResponse(intent)).thenReturn(response);

        mockMvc.perform(get("/api/v1/payments/intents/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void createIntent_returnsCreated() throws Exception {
        UUID id = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var intent = mockPaymentIntent(id);
        var response = mockIntentResponse(id);

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(paymentsService.createIntent(any(), any(), any())).thenReturn(intent);
        when(paymentIntentMapper.toResponse(intent)).thenReturn(response);

        mockMvc.perform(post("/api/v1/payments/intents")
                        .contentType("application/json")
                        .content("""
                                {"bookingId": "%s"}
                                """.formatted(bookingId)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void confirmIntent_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var intent = mockPaymentIntent(id);
        var response = mockIntentResponse(id);

        when(paymentsService.confirmIntent(any(), any())).thenReturn(intent);
        when(paymentIntentMapper.toResponse(intent)).thenReturn(response);

        mockMvc.perform(post("/api/v1/payments/intents/{id}/confirm", id)
                        .contentType("application/json")
                        .content("""
                                {"externalId": "ext-123"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void refundPayment_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var payment = mockPayment(id);
        var response = mockPaymentResponse(id);

        when(paymentsService.refundPayment(id)).thenReturn(payment);
        when(paymentMapper.toResponse(payment)).thenReturn(response);

        mockMvc.perform(post("/api/v1/payments/{paymentId}/refund", id))
                .andExpect(status().isOk());
    }

    @Test
    void webhook_returnsAccepted() throws Exception {
        when(paymentsService.processWebhookEvent(any(), any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/payments/webhooks/stripe")
                        .param("eventId", "evt_123")
                        .param("eventType", "payment.succeeded"))
                .andExpect(status().isAccepted());
    }

    private static PaymentIntent mockPaymentIntent(UUID id) {
        var intent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(intent.getId()).thenReturn(id);
        return intent;
    }

    private static PaymentIntentResponse mockIntentResponse(UUID id) {
        return new PaymentIntentResponse(id, null, null, null, null, null, null);
    }

    private static Payment mockPayment(UUID id) {
        var payment = org.mockito.Mockito.mock(Payment.class);
        when(payment.getId()).thenReturn(id);
        return payment;
    }

    private static PaymentResponse mockPaymentResponse(UUID id) {
        return new PaymentResponse(id, null, null, null, null);
    }
}
