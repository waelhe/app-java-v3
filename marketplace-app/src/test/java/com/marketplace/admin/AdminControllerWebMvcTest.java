package com.marketplace.admin;

import com.marketplace.booking.spi.BookingSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.payments.spi.PaymentsSpi;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.LedgerAllocationPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = AdminController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
@WithMockUser(roles = "ADMIN")
class AdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IdentitySpi identitySpi;

    @MockitoBean
    private CatalogSpi catalogSpi;

    @MockitoBean
    private BookingSpi bookingSpi;

    @MockitoBean
    private PaymentsSpi paymentsSpi;

    @MockitoBean
    private LedgerAllocationPort ledgerAllocationPort;

    @MockitoBean
    private RevisionService revisionService;

    @MockitoBean
    private SystemSettingsService systemSettings;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    @WithMockUser(roles = "USER")
    void updateUserRole_withUserRole_returnsForbidden() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/{id}/role", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"role": "ADMIN"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void listUsers_returnsOk() throws Exception {
        when(identitySpi.findAllSummaries(any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk());
    }

    @Test
    void listListings_returnsOk() throws Exception {
        when(catalogSpi.findAllSummaries(any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/admin/listings"))
                .andExpect(status().isOk());
    }

    @Test
    void listBookings_returnsOk() throws Exception {
        when(bookingSpi.listAllSummaries(any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/admin/bookings"))
                .andExpect(status().isOk());
    }

    @Test
    void listPayments_returnsOk() throws Exception {
        when(paymentsSpi.searchIntentsSummaries(any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/admin/payments"))
                .andExpect(status().isOk());
        verify(paymentsSpi).searchIntentsSummaries(any(), any());
    }

    @Test
    void searchPayments_withFilters_returnsOk() throws Exception {
        when(paymentsSpi.searchIntentsSummaries(any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/admin/payments")
                        .param("origin", "ORDER")
                        .param("status", "SUCCEEDED")
                        .param("subjectId", UUID.randomUUID().toString())
                        .param("consumerId", UUID.randomUUID().toString())
                        .param("createdAfter", "2026-01-01T00:00:00Z")
                        .param("createdBefore", "2026-02-01T00:00:00Z"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void searchPayments_withUserRole_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/payments"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPaymentIntent_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentsSpi.getIntentSummary(any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/admin/payments/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    void getFinancialFacts_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentsSpi.getIntentSummary(any())).thenReturn(null);
        when(ledgerAllocationPort.findByPaymentIntentId(any()))
                .thenReturn(LedgerAllocationPort.LedgerAllocation.empty());

        mockMvc.perform(get("/api/v1/admin/payments/{id}/financial-facts", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void getFinancialFacts_withUserRole_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/payments/{id}/financial-facts", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void settlementEligibility_delegatesToThePaymentsSpi() throws Exception {
        // W11 (the matrix's G5): the report's shape is the SPI's own wire
        // record — the admin surface composes, it never re-derives.
        UUID providerId = UUID.randomUUID();
        when(paymentsSpi.settlementEligibility(eq(providerId), isNull(), any()))
                .thenReturn(new com.marketplace.payments.spi.SettlementEligibilityReport(
                        providerId,
                        java.util.List.of(new com.marketplace.shared.api.LedgerAllocationPort.ProviderBalanceFact(
                                providerId, "SAR", 4500L)),
                        java.util.List.of(new com.marketplace.payments.spi.SettlementEligibilityReport.OriginCurrencyTotal(
                                "BOOKING", "SAR", 1, 5000L, 5000L)),
                        new com.marketplace.shared.api.PagedResponse<>(java.util.List.of(), 0, 20, 0, 0, true)));

        mockMvc.perform(get("/api/v1/admin/payments/settlement-eligibility")
                        .param("providerId", providerId.toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"providerId":"%s","balances":[{"currency":"SAR","balanceCents":4500}],
                         "totals":[{"origin":"BOOKING","currency":"SAR","creditCount":1,
                                    "creditedCents":5000,"eligibleCents":5000}]}
                        """.formatted(providerId)));
        verify(paymentsSpi).settlementEligibility(eq(providerId), isNull(), any());
    }

    @Test
    void settlementEligibility_originFilterRidesTheSeam() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(paymentsSpi.settlementEligibility(eq(providerId), eq("ORDER"), any()))
                .thenReturn(new com.marketplace.payments.spi.SettlementEligibilityReport(
                        providerId, java.util.List.of(), java.util.List.of(),
                        new com.marketplace.shared.api.PagedResponse<>(java.util.List.of(), 0, 20, 0, 0, true)));

        mockMvc.perform(get("/api/v1/admin/payments/settlement-eligibility")
                        .param("providerId", providerId.toString())
                        .param("origin", "ORDER"))
                .andExpect(status().isOk());
        verify(paymentsSpi).settlementEligibility(eq(providerId), eq("ORDER"), any());
    }

    @Test
    @WithMockUser(roles = "USER")
    void settlementEligibility_withUserRole_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/payments/settlement-eligibility")
                        .param("providerId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void settlementEligibility_missingProviderId_answers400() throws Exception {
        // Eligibility is a per-provider question — the absent id is a
        // client error, never a platform-wide unbounded walk.
        mockMvc.perform(get("/api/v1/admin/payments/settlement-eligibility"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listAuditedEntities_returnsOk() throws Exception {
        when(revisionService.getEntityNames()).thenReturn(java.util.Set.of());

        mockMvc.perform(get("/api/v1/admin/revisions/entities"))
                .andExpect(status().isOk());
    }

    @Test
    void updateUserRole_returnsOk() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/{id}/role", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"role": "ADMIN"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void purgeUserContent_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        when(identitySpi.purgeAuthoredContent(any(), any(), any())).thenReturn(12);

        mockMvc.perform(post("/api/v1/admin/users/{id}/purge-content", id)
                        .contentType("application/json")
                        .content("""
                                {"reason": "legal erasure demand"}
                                """))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("{\"purgedRows\": 12}"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void purgeUserContent_withUserRole_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/{id}/purge-content", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"reason": "must be rejected"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void purgeUserContent_withBlankReason_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/{id}/purge-content", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"reason": "  "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void purgeUserAuditHistory_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        when(identitySpi.purgeAuditHistory(any(), any(), any()))
                .thenReturn(new com.marketplace.identity.spi.AuditHistoryPurgeResult(15, 3));

        mockMvc.perform(post("/api/v1/admin/users/{id}/purge-audit-history", id)
                        .contentType("application/json")
                        .content("""
                                {"reason": "legal erasure demand"}
                                """))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("{\"scrubbedRows\": 15, \"usersAudRowsDeleted\": 3}"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void purgeUserAuditHistory_withUserRole_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/{id}/purge-audit-history", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"reason": "must be rejected"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void purgeUserAuditHistory_withBlankReason_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/{id}/purge-audit-history", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"reason": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    // -- System settings (W0 — yelp-level plan §4.6) --------------------------

    private static SystemSetting setting(String key, String json) {
        return SystemSetting.create(UUID.randomUUID(), key,
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(json), "test");
    }

    @Test
    void listSystemSettings_returnsOk() throws Exception {
        when(systemSettings.findAll(any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/admin/settings"))
                .andExpect(status().isOk());
    }

    @Test
    void getSystemSetting_returnsTheStoredJsonValue() throws Exception {
        when(systemSettings.findByKey("reviews.mode")).thenReturn(setting("reviews.mode", "\"VERIFIED_ONLY\""));

        mockMvc.perform(get("/api/v1/admin/settings/{key}", "reviews.mode"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.key").value("reviews.mode"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.value").value("VERIFIED_ONLY"));
    }

    @Test
    void createSystemSetting_returnsCreated() throws Exception {
        when(systemSettings.create(any(), any(), any(), any())).thenReturn(setting("reviews.mode", "\"OPEN\""));

        mockMvc.perform(post("/api/v1/admin/settings")
                        .contentType("application/json")
                        .content("""
                                {"key": "reviews.mode", "value": "OPEN", "description": "creation gate"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.value").value("OPEN"));
    }

    @Test
    void createSystemSetting_withBlankKey_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/settings")
                        .contentType("application/json")
                        .content("""
                                {"key": " ", "value": "OPEN"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateSystemSetting_withEmptyBody_returnsBadRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/settings/{key}", "reviews.mode")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "USER")
    void updateSystemSetting_withUserRole_returnsForbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/settings/{key}", "reviews.mode")
                        .contentType("application/json")
                        .content("""
                                {"value": "OPEN"}
                                """))
                .andExpect(status().isForbidden());
    }

    // -- The multi-role surface (ADR-0001's assignment model, delegated
    //    through IdentitySpi to #543's RoleAssignmentService semantics) --

    @Test
    void getUserRoles_returnsTheActiveSet() throws Exception {
        when(identitySpi.getUserRoles(any())).thenReturn(Set.of("CONSUMER", "PROVIDER"));

        mockMvc.perform(get("/api/v1/admin/users/{id}/roles", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"CONSUMER\",\"PROVIDER\"]"));
    }

    @Test
    void grantUserRole_delegatesAndReturnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/{id}/roles", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"role": "PROVIDER"}
                                """))
                .andExpect(status().isOk());
        verify(identitySpi).grantUserRole(any(), eq("PROVIDER"), any());
    }

    @Test
    void grantUserRole_withInvalidRoleName_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/{id}/roles", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"role": "SUPERUSER"}
                                """))
                .andExpect(status().isBadRequest());
        verify(identitySpi, org.mockito.Mockito.never()).grantUserRole(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "USER")
    void grantUserRole_withUserRole_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/{id}/roles", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"role": "PROVIDER"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void grantUserRole_duplicateActivePair_answers409() throws Exception {
        doThrow(new ConflictException(
                "An active PROVIDER assignment already exists for this account"))
                .when(identitySpi).grantUserRole(any(), eq("PROVIDER"), any());

        mockMvc.perform(post("/api/v1/admin/users/{id}/roles", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"role": "PROVIDER"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void revokeUserRole_delegatesAndReturnsOk() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/users/{id}/roles/{role}", UUID.randomUUID(), "PROVIDER"))
                .andExpect(status().isOk());
        verify(identitySpi).revokeUserRole(any(), eq("PROVIDER"), any());
    }

    @Test
    void revokeUserRole_primaryRoleRefusal_answers409() throws Exception {
        doThrow(new ConflictException(
                "The account's primary role refuses the revoke — its honest path is the role update"))
                .when(identitySpi).revokeUserRole(any(), eq("CONSUMER"), any());

        mockMvc.perform(delete("/api/v1/admin/users/{id}/roles/{role}", UUID.randomUUID(), "CONSUMER"))
                .andExpect(status().isConflict());
    }
}
