package com.marketplace.admin;

import com.marketplace.booking.spi.BookingSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.payments.spi.PaymentsSpi;
import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BookingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PaymentSummary;
import com.marketplace.shared.api.ProviderListingSummary;
import com.marketplace.shared.api.UserSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiConstants.ADMIN)
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final IdentitySpi identitySpi;
    private final CatalogSpi catalogSpi;
    private final BookingSpi bookingSpi;
    private final PaymentsSpi paymentsSpi;

    public AdminController(IdentitySpi identitySpi,
                           CatalogSpi catalogSpi,
                           BookingSpi bookingSpi,
                           PaymentsSpi paymentsSpi) {
        this.identitySpi = identitySpi;
        this.catalogSpi = catalogSpi;
        this.bookingSpi = bookingSpi;
        this.paymentsSpi = paymentsSpi;
    }

    // ── Users ──────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<PagedResponse<UserSummary>> listUsers(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(identitySpi.findAllSummaries(pageable)));
    }

    // ── Listings ───────────────────────────────────────

    @GetMapping("/listings")
    public ResponseEntity<PagedResponse<ProviderListingSummary>> listAllListings(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogSpi.findAllSummaries(pageable)));
    }

    @PostMapping("/listings/{id}/archive")
    public ResponseEntity<ProviderListingSummary> archiveListing(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(catalogSpi.archiveListing(id, authentication));
    }

    // ── Bookings ───────────────────────────────────────

    @GetMapping("/bookings")
    public ResponseEntity<PagedResponse<BookingSummary>> listBookings(
            @RequestParam(required = false) String status, Pageable pageable) {
        Page<BookingSummary> bookings = status != null && !status.isBlank()
                ? bookingSpi.listByStatusSummary(status, pageable)
                : bookingSpi.listAllSummaries(pageable);
        return ResponseEntity.ok(PagedResponse.of(bookings));
    }

    // ── Payments ──────────────────────────────────────

    @GetMapping("/payments")
    public ResponseEntity<PagedResponse<PaymentSummary>> listPaymentIntents(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(paymentsSpi.listIntentsSummaries(pageable)));
    }

    @GetMapping("/payments/{id}")
    public ResponseEntity<PaymentSummary> getPaymentIntent(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentsSpi.getIntentSummary(id));
    }
}
