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
    public ResponseEntity<BookingResponse> getById(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(BookingResponse.from(bookingService.getByIdForUser(id, authentication)));
    }

    @GetMapping("/consumer/{consumerId}")
    public ResponseEntity<PagedResponse<BookingResponse>> listByConsumer(
            @PathVariable UUID consumerId, Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                bookingService.listByConsumer(consumerId, pageable, authentication).map(BookingResponse::from)));
    }

    @GetMapping("/provider/{providerId}")
    public ResponseEntity<PagedResponse<BookingResponse>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                bookingService.listByProvider(providerId, pageable, authentication).map(BookingResponse::from)));
    }

    @PostMapping
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request,
                                                  Authentication authentication) {
        UUID consumerId = currentUserProvider.getCurrentUserId(authentication);
        Booking booking = bookingService.create(
                consumerId, request.listingId(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(BookingResponse.from(booking));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    public ResponseEntity<BookingResponse> confirm(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(BookingResponse.from(bookingService.confirm(id, authentication)));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    public ResponseEntity<BookingResponse> complete(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(BookingResponse.from(bookingService.complete(id, authentication)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('CONSUMER','PROVIDER')")
    public ResponseEntity<BookingResponse> cancel(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(BookingResponse.from(bookingService.cancel(id, authentication)));
    }

    public record CreateBookingRequest(
            @NotNull UUID listingId,
            String notes
    ) {
    }
}
