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
 * resolution and the 404 for a caller without a provider profile.
 *
 * <p><b>R10 regression pin (frontend battery card BE-04, LEDGER-403):</b>
 * the "me" resolution must pass the authenticated USER id into the
 * service — the A1 cross-module {@code provider_id} space — never the
 * {@code provider_profiles.PK}. The pre-fix code passed the PK; the mock
 * here stubbed the service with that same PK and the defect sailed through
 * this very slice. The stubs and the explicit {@code verify(...)} below now
 * pin the id SPACE, not just the route.
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

    /** The profile PK created by the last stubOwnProvider call — the id that must NEVER reach the service (the BE-04 trap). */
    private UUID lastProfileId;

    private UUID stubOwnProvider() {
        UUID userId = UUID.randomUUID();
        UUID providerProfileId = UUID.randomUUID(); // deliberately DIFFERENT from userId — the two ID spaces must never be conflated (the BE-04 trap)
        lastProfileId = providerProfileId;
        when(currentUserProvider.getCurrentUserId(ArgumentMatchers.<Authentication>any())).thenReturn(userId);
        when(providerLookupPort.findByUserId(userId)).thenReturn(Optional.of(
                new ProviderSummary(providerProfileId, "Test Provider", "VERIFIED", userId)));
        return userId; // the id the service must receive — the USER id, not the profile PK
    }

    @Test
    void getMyBalance_returnsOneRowPerCurrencyHeld() throws Exception {
        UUID userId = stubOwnProvider();
        ProviderBalance sarCredited = ProviderBalance.empty(userId, "SAR");
        sarCredited.credit(4500L);
        ProviderBalance usdCredited = ProviderBalance.empty(userId, "USD");
        usdCredited.credit(1200L);
        when(ledgerService.getBalancesForOwner(userId))
                .thenReturn(List.of(ProviderBalanceResponse.from(sarCredited),
                        ProviderBalanceResponse.from(usdCredited)));

        mockMvc.perform(get("/api/v1/providers/me/ledger/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(userId.toString()))
                .andExpect(jsonPath("$[0].currency").value("SAR"))
                .andExpect(jsonPath("$[0].availableCents").value(4500L))
                .andExpect(jsonPath("$[1].currency").value("USD"))
                .andExpect(jsonPath("$[1].availableCents").value(1200L));

        // BE-04 regression pin: the service receives the USER id (the A1
        // cross-module space), never the provider_profiles.PK — the exact
        // argument mismatch that made the legitimate owner always-denied.
        org.mockito.Mockito.verify(ledgerService).getBalancesForOwner(
                org.mockito.ArgumentMatchers.eq(userId));
        org.mockito.Mockito.verify(ledgerService, org.mockito.Mockito.never())
                .getBalancesForOwner(org.mockito.ArgumentMatchers.eq(lastProfileId));
    }

    @Test
    void getMyStatement_returnsPagedMovements() throws Exception {
        UUID userId = stubOwnProvider();
        UUID sourceId = UUID.randomUUID();
        when(ledgerService.getStatementForOwner(org.mockito.ArgumentMatchers.eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(LedgerEntry.paymentCredit(userId, sourceId, 5000L, "SAR")),
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
     * reproduces the balance PER CURRENCY (5000 credit − 500 commission =
     * 4500 SAR; R9 — entries carry their currency).
     */
    @Test
    void getMyStatement_commissionDebitPresentsNegativeAmount() throws Exception {
        UUID userId = stubOwnProvider();
        UUID paymentIntentId = UUID.randomUUID();
        UUID commissionSourceId = UUID.nameUUIDFromBytes(
                ("commission-" + paymentIntentId).getBytes());
        when(ledgerService.getStatementForOwner(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(
                                LedgerEntry.paymentCredit(userId, paymentIntentId, 5000L, "SAR"),
                                LedgerEntry.commissionDebit(userId, commissionSourceId, 500L, "SAR")),
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
