package com.marketplace.admin;

import com.marketplace.booking.spi.BookingSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.payments.spi.PaymentsSpi;
import com.marketplace.shared.api.PagedResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private IdentitySpi identitySpi;

    @Mock
    private CatalogSpi catalogSpi;

    @Mock
    private BookingSpi bookingSpi;

    @Mock
    private PaymentsSpi paymentsSpi;

    @InjectMocks
    private AdminController controller;

    @Test
    void listUsers_returnsPage() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(identitySpi.findAllSummaries(pageable)).thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<PagedResponse<com.marketplace.shared.api.UserSummary>> result = controller.listUsers(pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void listAllListings_returnsPage() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(catalogSpi.findAllSummaries(pageable)).thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<PagedResponse<com.marketplace.shared.api.ProviderListingSummary>> result = controller.listAllListings(pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void archiveListing_returnsSummary() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        var summary = mock(com.marketplace.shared.api.ProviderListingSummary.class);
        when(catalogSpi.archiveListing(id, auth)).thenReturn(summary);

        ResponseEntity<com.marketplace.shared.api.ProviderListingSummary> result = controller.archiveListing(id, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void listBookings_withStatus() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(bookingSpi.listByStatusSummary("PENDING", pageable)).thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<PagedResponse<com.marketplace.shared.api.BookingSummary>> result = controller.listBookings("PENDING", pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void listBookings_withoutStatus() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(bookingSpi.listAllSummaries(pageable)).thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<PagedResponse<com.marketplace.shared.api.BookingSummary>> result = controller.listBookings(null, pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void listPaymentIntents_returnsPage() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(paymentsSpi.listIntentsSummaries(pageable)).thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<PagedResponse<com.marketplace.shared.api.PaymentSummary>> result = controller.listPaymentIntents(pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void getPaymentIntent_returnsSummary() {
        UUID id = UUID.randomUUID();
        var summary = mock(com.marketplace.shared.api.PaymentSummary.class);
        when(paymentsSpi.getIntentSummary(id)).thenReturn(summary);

        ResponseEntity<com.marketplace.shared.api.PaymentSummary> result = controller.getPaymentIntent(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }
}
