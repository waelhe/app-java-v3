package com.marketplace.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LedgerController.class)
@WithMockUser(roles = "ADMIN")
class LedgerControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LedgerService ledgerService;

    @Test
    void creditProvider_returnsOk() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        when(ledgerService.creditFromPayment(any(), any(), anyLong())).thenReturn(org.mockito.Mockito.mock(ProviderBalance.class));

        mockMvc.perform(post("/api/v1/admin/ledger/providers/{providerId}/credit", providerId)
                        .param("paymentIntentId", paymentIntentId.toString())
                        .param("amountCents", "5000"))
                .andExpect(status().isOk());
    }

    @Test
    void getProviderBalance_returnsOk() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(ledgerService.getBalance(any())).thenReturn(org.mockito.Mockito.mock(ProviderBalance.class));

        mockMvc.perform(get("/api/v1/admin/ledger/providers/{providerId}/balance", providerId))
                .andExpect(status().isOk());
    }
}
