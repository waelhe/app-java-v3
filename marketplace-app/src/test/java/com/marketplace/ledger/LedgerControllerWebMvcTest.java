package com.marketplace.ledger;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = LedgerController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
@WithMockUser(roles = "ADMIN")
class LedgerControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LedgerService ledgerService;

    // Round 5: the controller's guard dependency (the settlement listener)
    // is not part of the WebMvc slice — the mock keeps the slice's context
    // bootable; the guard's own contract tests live beside the listener.
    @MockitoBean
    private LedgerPaymentEventListener settlementListener;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    @WithMockUser(roles = "USER")
    void creditProvider_withUserRole_returnsForbidden() throws Exception {
        UUID providerId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/ledger/providers/{providerId}/credit", providerId)
                        .param("paymentIntentId", UUID.randomUUID().toString())
                        .param("amountCents", "5000"))
                .andExpect(status().isForbidden());
    }

    @Test
    void creditProvider_returnsOk() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        when(ledgerService.creditFromPayment(any(), any(), anyLong(), any())).thenReturn(
                new ProviderBalanceResponse(providerId, "SAR", 5000L, null, null, null, null, null));

        mockMvc.perform(post("/api/v1/admin/ledger/providers/{providerId}/credit", providerId)
                        .param("paymentIntentId", paymentIntentId.toString())
                        .param("amountCents", "5000"))
                .andExpect(status().isOk());
    }

    @Test
    void getProviderBalances_returnsOk() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(ledgerService.getBalances(any())).thenReturn(java.util.List.of(
                new ProviderBalanceResponse(providerId, "SAR", 5000L, null, null, null, null, null),
                new ProviderBalanceResponse(providerId, "USD", 1200L, null, null, null, null, null)));

        mockMvc.perform(get("/api/v1/admin/ledger/providers/{providerId}/balance", providerId))
                .andExpect(status().isOk());
    }

    @Test
    void creditProvider_mapsTheSettlementAmbiguityGuardRefusalToVal001() throws Exception {
        // CodeRabbit round 8 (the nitpick — the declared HTTP contract of
        // the round-5/6/7 front door): the listener's own unit tests prove
        // the guard throws and blocks the write; this MVC case pins the
        // exception-to-response mapping the endpoint actually declares — a
        // guard refusal is a 400 VAL-001 (the VALIDATION taxonomy, A5:
        // an illegal argument is a client-contract violation, never a
        // 500), and the credit never reaches the ledger.
        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalArgumentException(
                        "A manual credit cannot target a zero-commission fee-bearing order intent"))
                .when(settlementListener).assertCreditTargetIsNotSettlementAmbiguous(paymentIntentId);

        mockMvc.perform(post("/api/v1/admin/ledger/providers/{providerId}/credit", providerId)
                        .param("paymentIntentId", paymentIntentId.toString())
                        .param("amountCents", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VAL-001"));

        verify(ledgerService, never()).creditFromPayment(any(), any(), anyLong(), any());
    }
}
