package com.marketplace.admin;

import com.marketplace.booking.spi.BookingSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.payments.spi.PaymentsSpi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    private RevisionService revisionService;

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
        when(paymentsSpi.listIntentsSummaries(any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/admin/payments"))
                .andExpect(status().isOk());
    }

    @Test
    void getPaymentIntent_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentsSpi.getIntentSummary(any())).thenReturn(null);

        mockMvc.perform(get("/api/v1/admin/payments/{id}", id))
                .andExpect(status().isOk());
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
}
