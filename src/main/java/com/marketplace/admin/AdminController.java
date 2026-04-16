package com.marketplace.admin;

import com.marketplace.booking.Booking;
import com.marketplace.booking.BookingRepository;
import com.marketplace.booking.BookingStatus;
import com.marketplace.catalog.ProviderListing;
import com.marketplace.catalog.ProviderListingRepository;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentIntentRepository;
import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiConstants.ADMIN)
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserRepository userRepository;
    private final ProviderListingRepository listingRepository;
    private final BookingRepository bookingRepository;
    private final PaymentIntentRepository paymentIntentRepository;

    public AdminController(UserRepository userRepository,
                           ProviderListingRepository listingRepository,
                           BookingRepository bookingRepository,
                           PaymentIntentRepository paymentIntentRepository) {
        this.userRepository = userRepository;
        this.listingRepository = listingRepository;
        this.bookingRepository = bookingRepository;
        this.paymentIntentRepository = paymentIntentRepository;
    }

    // ── Users ──────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<PagedResponse<User>> listUsers(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(userRepository.findAll(pageable)));
    }

    // ── Listings ───────────────────────────────────────

    @GetMapping("/listings")
    public ResponseEntity<PagedResponse<ProviderListing>> listAllListings(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(listingRepository.findAll(pageable)));
    }

    @PostMapping("/listings/{id}/archive")
    public ResponseEntity<ProviderListing> archiveListing(@PathVariable UUID id) {
        return listingRepository.findById(id)
                .map(listing -> {
                    listing.archive();
                    return ResponseEntity.ok(listing);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Bookings ───────────────────────────────────────

    @GetMapping("/bookings")
    public ResponseEntity<PagedResponse<Booking>> listBookings(
            @RequestParam(required = false) BookingStatus status, Pageable pageable) {
        Page<Booking> bookings = status != null
                ? bookingRepository.findByStatus(status, pageable)
                : bookingRepository.findAll(pageable);
        return ResponseEntity.ok(PagedResponse.of(bookings));
    }

    // ── Payments ──────────────────────────────────────

    @GetMapping("/payments")
    public ResponseEntity<PagedResponse<PaymentIntent>> listPaymentIntents(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(paymentIntentRepository.findAll(pageable)));
    }

    @GetMapping("/payments/{id}")
    public ResponseEntity<PaymentIntent> getPaymentIntent(@PathVariable UUID id) {
        return paymentIntentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}