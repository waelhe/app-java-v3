package com.marketplace.ledger;

import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

/**
 * L20 HTTP contract (roadmap §5): the two provider "me" ledger endpoints.
 * Authorization itself (ownsProvider) is pinned in
 * {@code LedgerServiceSecurityTest}; this slice pins the routes, the "me"
 * resolution (user → provider via {@link ProviderLookupPort}) and the 404
 * for a caller without a provider profile.
 */
@WebMvcTest(controllers = ProviderLedgerController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
@WithMockUser
class ProviderLedgerControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LedgerService ledgerService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ProviderLookupPort providerLookupPort;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private UUID stubOwnProvider() {
        UUID userId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(ArgumentMatchers.<Authentication>any())).thenReturn(userId);
        when(providerLookupPort.findByUserId(userId)).thenReturn(Optional.of(
                new ProviderSummary(providerId, "Test Provider", "VERIFIED", userId)));
        return providerId;
    }

    @Test
    void getMyBalance_returnsOwnBalance() throws Exception {
        UUID providerId = stubOwnProvider();
        ProviderBalance balance = ProviderBalance.empty(providerId);
        balance.credit(4500L);
        when(ledgerService.getBalanceForOwner(providerId)).thenReturn(balance);

        mockMvc.perform(get("/api/v1/providers/me/ledger/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(providerId.toString()))
                .andExpect(jsonPath("$.availableCents").value(4500L));
    }

    @Test
    void getMyStatement_returnsPagedMovements() throws Exception {
        UUID providerId = stubOwnProvider();
        UUID sourceId = UUID.randomUUID();
        when(ledgerService.getStatementForOwner(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(LedgerEntry.paymentCredit(providerId, sourceId, 5000L)),
                        PageRequest.of(0, 20),
                        1));

        mockMvc.perform(get("/api/v1/providers/me/ledger/statement")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sourceId").value(sourceId.toString()))
                .andExpect(jsonPath("$.content[0].entryType").value("PAYMENT_CREDIT"))
                .andExpect(jsonPath("$.content[0].amountCents").value(5000L))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    /**
     * Signed statement contract (CodeRabbit #248 round 1): the commission
     * debit presents a NEGATIVE amountCents so a client summing the page
     * reproduces the balance (5000 credit − 500 commission = 4500).
     */
    @Test
    void getMyStatement_commissionDebitPresentsNegativeAmount() throws Exception {
        UUID providerId = stubOwnProvider();
        UUID paymentIntentId = UUID.randomUUID();
        UUID commissionSourceId = UUID.nameUUIDFromBytes(
                ("commission-" + paymentIntentId).getBytes());
        when(ledgerService.getStatementForOwner(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(
                                LedgerEntry.paymentCredit(providerId, paymentIntentId, 5000L),
                                LedgerEntry.commissionDebit(providerId, commissionSourceId, 500L)),
                        PageRequest.of(0, 20),
                        2));

        mockMvc.perform(get("/api/v1/providers/me/ledger/statement")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[1].entryType").value("COMMISSION_DEBIT"))
                .andExpect(jsonPath("$.content[1].amountCents").value(-500L))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getMyBalance_withoutProviderProfile_returnsNotFound() throws Exception {
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(ArgumentMatchers.<Authentication>any())).thenReturn(userId);
        when(providerLookupPort.findByUserId(userId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/providers/me/ledger/balance"))
                .andExpect(status().isNotFound());
    }
}
