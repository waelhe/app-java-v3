package com.marketplace.admin;

import com.marketplace.booking.BookingService;
import com.marketplace.catalog.CatalogService;
import com.marketplace.catalog.ProviderListing;
import com.marketplace.identity.UserService;
import com.marketplace.payments.PaymentsService;
import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BookingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PaymentSummary;
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

    private final UserService userService;
    private final CatalogService catalogService;
    private final BookingService bookingService;
    private final PaymentsService paymentsService;

    public AdminController(UserService userService,
                           CatalogService catalogService,
                           BookingService bookingService,
                           PaymentsService paymentsService) {
        this.userService = userService;
        this.catalogService = catalogService;
        this.bookingService = bookingService;
        this.paymentsService = paymentsService;
    }

    // ── Users ──────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<PagedResponse<UserSummary>> listUsers(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(userService.findAllSummaries(pageable)));
    }

    // ── Listings ───────────────────────────────────────

    @GetMapping("/listings")
    public ResponseEntity<PagedResponse<ProviderListing>> listAllListings(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.findAll(pageable)));
    }

    @PostMapping("/listings/{id}/archive")
    public ResponseEntity<ProviderListing> archiveListing(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(catalogService.archive(id, authentication));
    }

    // ── Bookings ───────────────────────────────────────

    @GetMapping("/bookings")
    public ResponseEntity<PagedResponse<BookingSummary>> listBookings(
            @RequestParam(required = false) String status, Pageable pageable) {
        Page<BookingSummary> bookings = status != null && !status.isBlank()
                ? bookingService.listByStatusSummary(status, pageable)
                : bookingService.listAllSummaries(pageable);
        return ResponseEntity.ok(PagedResponse.of(bookings));
    }

    // ── Payments ──────────────────────────────────────

    @GetMapping("/payments")
    public ResponseEntity<PagedResponse<PaymentSummary>> listPaymentIntents(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(paymentsService.listIntentsSummaries(pageable)));
    }

    @GetMapping("/payments/{id}")
    public ResponseEntity<PaymentSummary> getPaymentIntent(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentsService.getIntentSummary(id));
    }
}
