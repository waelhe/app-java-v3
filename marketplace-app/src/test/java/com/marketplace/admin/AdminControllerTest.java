package com.marketplace.admin;

import com.marketplace.booking.spi.BookingSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.payments.spi.PaymentsSpi;
import com.marketplace.shared.api.BookingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PaymentSummary;
import com.marketplace.shared.api.ProviderListingSummary;
import com.marketplace.shared.api.UserSummary;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final IdentitySpi identitySpi = mock(IdentitySpi.class);
    private final CatalogSpi catalogSpi = mock(CatalogSpi.class);
    private final BookingSpi bookingSpi = mock(BookingSpi.class);
    private final PaymentsSpi paymentsSpi = mock(PaymentsSpi.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new AdminController(identitySpi, catalogSpi, bookingSpi, paymentsSpi))
            .setApiVersionStrategy(apiVersionStrategy())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
    private final UUID listingId = UUID.randomUUID();

    @Test
    void listUsers() throws Exception {
        when(identitySpi.findAllSummaries(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void listAllListings() throws Exception {
        when(catalogSpi.findAllSummaries(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/listings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void archiveListing() throws Exception {
        when(catalogSpi.archiveListing(any(), any())).thenReturn(mock(ProviderListingSummary.class));

        mockMvc.perform(post("/api/v1/admin/listings/{id}/archive", listingId))
                .andExpect(status().isOk());
    }

    @Test
    void listBookings() throws Exception {
        when(bookingSpi.listAllSummaries(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void listBookingsByStatus() throws Exception {
        when(bookingSpi.listByStatusSummary(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/bookings")
                        .param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void listPaymentIntents() throws Exception {
        when(paymentsSpi.listIntentsSummaries(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void getPaymentIntent() throws Exception {
        when(paymentsSpi.getIntentSummary(any())).thenReturn(mock(PaymentSummary.class));

        mockMvc.perform(get("/api/v1/admin/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isOk());
    }
}
