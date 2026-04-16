package com.marketplace.booking;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiConstants.BOOKING)
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
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
    public ResponseEntity<Booking> create(@RequestBody CreateBookingRequest request) {
        Booking booking = bookingService.create(
                request.consumerId(), request.providerId(), request.listingId(),
                request.priceCents(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(booking);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<Booking> confirm(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingService.confirm(id));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<Booking> complete(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingService.complete(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('CONSUMER','PROVIDER')")
    public ResponseEntity<Booking> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(bookingService.cancel(id));
    }

    public record CreateBookingRequest(
            @NotNull UUID consumerId,
            @NotNull UUID providerId,
            @NotNull UUID listingId,
            @NotNull Long priceCents,
            String notes
    ) {}
}