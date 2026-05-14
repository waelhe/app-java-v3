package com.marketplace.payments;

import com.marketplace.shared.security.CurrentUserProvider;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentsControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final PaymentsService paymentsService = mock(PaymentsService.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new PaymentsController(paymentsService, currentUserProvider))
            .setApiVersionStrategy(apiVersionStrategy())
            .build();
    private static PaymentIntent aPaymentIntent() {
        return new PaymentIntent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1000L, null);
    }

    private static Payment aPayment() {
        return new Payment(UUID.randomUUID(), UUID.randomUUID(), 1000L);
    }

    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    private static void asConsumer() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("consumer", "",
                        List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"))));
    }

    private static void asAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test
    void getIntent() throws Exception {
        when(paymentsService.getIntentForUser(any(), any())).thenReturn(aPaymentIntent());

        mockMvc.perform(get("/api/v1/payments/intents/{id}", paymentId))
                .andExpect(status().isOk());
    }

    @Test
    void createIntent() throws Exception {
        asConsumer();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        when(paymentsService.createIntent(any(), any(), any())).thenReturn(aPaymentIntent());

        mockMvc.perform(post("/api/v1/payments/intents")
                        .contentType("application/json")
                        .content("{\"bookingId\": \"" + UUID.randomUUID() + "\", \"idempotencyKey\": \"idem-1\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void processIntent() throws Exception {
        asConsumer();
        when(paymentsService.processIntent(any(), any())).thenReturn(aPaymentIntent());

        mockMvc.perform(post("/api/v1/payments/intents/{id}/process", paymentId))
                .andExpect(status().isOk());
    }

    @Test
    void cancelIntent() throws Exception {
        asConsumer();
        when(paymentsService.cancelIntent(any(), any())).thenReturn(aPaymentIntent());

        mockMvc.perform(post("/api/v1/payments/intents/{id}/cancel", paymentId))
                .andExpect(status().isOk());
    }

    @Test
    void confirmIntent() throws Exception {
        asAdmin();
        when(paymentsService.confirmIntent(any(UUID.class), any(String.class))).thenReturn(aPaymentIntent());

        mockMvc.perform(post("/api/v1/payments/intents/{id}/confirm", paymentId)
                        .contentType("application/json")
                        .content("{\"externalId\": \"ext-123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void refundPayment() throws Exception {
        asAdmin();
        when(paymentsService.refundPayment(any(UUID.class))).thenReturn(aPayment());

        mockMvc.perform(post("/api/v1/payments/{paymentId}/refund", paymentId))
                .andExpect(status().isOk());
    }

    @Test
    void createIntent_validationError() throws Exception {
        asConsumer();

        mockMvc.perform(post("/api/v1/payments/intents")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webhook() throws Exception {
        when(paymentsService.processWebhookEvent(any(), any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/payments/webhooks/{provider}", "stripe")
                        .param("eventId", "evt_123")
                        .param("eventType", "payment_intent.succeeded"))
                .andExpect(status().isAccepted());
    }

    @Test
    void webhook_noCreation() throws Exception {
        when(paymentsService.processWebhookEvent(any(), any(), any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/payments/webhooks/{provider}", "stripe")
                        .param("eventId", "evt_123")
                        .param("eventType", "payment_intent.succeeded"))
                .andExpect(status().isOk());
    }
}
