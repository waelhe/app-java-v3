package com.marketplace.booking;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiConstants.BOOKING)
public class BookingController {

    private final BookingService bookingService;
    private final CurrentUserProvider currentUserProvider;

    public BookingController(BookingService bookingService, CurrentUserProvider currentUserProvider) {
        this.bookingService = bookingService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Booking> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingService.getById(id));
    }

    @GetMapping("/consumer/{consumerId}")
    public ResponseEntity<PagedResponse<Booking>> listByConsumer(
            @PathVariable UUID consumerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(bookingService.listByConsumer(consumerId, pageable)));
    }

    @GetMapping("/provider/{providerId}")
    public ResponseEntity<PagedResponse<Booking>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(bookingService.listByProvider(providerId, pageable)));
    }

    @PostMapping
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<Booking> create(@Valid @RequestBody CreateBookingRequest request,
                                           Authentication authentication) {
        UUID consumerId = currentUserProvider.getCurrentUserId(authentication);
        Booking booking = bookingService.create(
                consumerId, request.providerId(), request.listingId(),
                request.priceCents(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(booking);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<Booking> confirm(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(bookingService.confirm(id, authentication));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<Booking> complete(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(bookingService.complete(id, authentication));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('CONSUMER','PROVIDER')")
    public ResponseEntity<Booking> cancel(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(bookingService.cancel(id, authentication));
    }

    public record CreateBookingRequest(
            @NotNull UUID providerId,
            @NotNull UUID listingId,
            @NotNull Long priceCents,
            String notes
    ) {}
}