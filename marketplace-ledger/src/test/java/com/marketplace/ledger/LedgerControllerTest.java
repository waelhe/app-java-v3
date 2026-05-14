package com.marketplace.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LedgerControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final LedgerService ledgerService = mock(LedgerService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new LedgerController(ledgerService))
            .setApiVersionStrategy(apiVersionStrategy())
            .build();

    @Test
    void creditProvider() throws Exception {
        when(ledgerService.creditFromPayment(any(), any(), anyLong())).thenReturn(mock(ProviderBalance.class));

        mockMvc.perform(post("/api/v1/admin/ledger/providers/{providerId}/credit", UUID.randomUUID())
                        .param("paymentIntentId", UUID.randomUUID().toString())
                        .param("amountCents", "5000"))
                .andExpect(status().isOk());
    }

    @Test
    void getProviderBalance() throws Exception {
        when(ledgerService.getBalance(any())).thenReturn(mock(ProviderBalance.class));

        mockMvc.perform(get("/api/v1/admin/ledger/providers/{providerId}/balance", UUID.randomUUID()))
                .andExpect(status().isOk());
    }
}
