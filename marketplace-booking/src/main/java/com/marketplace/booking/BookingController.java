package com.marketplace.booking;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.BOOKING, version = "1.0")
public class BookingController {

    private final BookingService bookingService;
    private final CurrentUserProvider currentUserProvider;
    private final BookingMapper bookingMapper;

    public BookingController(BookingService bookingService, CurrentUserProvider currentUserProvider, BookingMapper bookingMapper) {
        this.bookingService = bookingService;
        this.currentUserProvider = currentUserProvider;
        this.bookingMapper = bookingMapper;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one booking", description = "Participant-scoped: the consumer or the "
            + "provider of the booking (or ADMIN); anyone else gets 404.")
    public ResponseEntity<BookingResponse> getById(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(bookingMapper.toResponse(bookingService.getByIdForUser(id, authentication)));
    }

    @GetMapping("/consumer/{consumerId}")
    @Operation(summary = "List a consumer's bookings", description = "Paginated bookings placed by the "
            + "given consumer. Self or ADMIN only.")
    public ResponseEntity<PagedResponse<BookingResponse>> listByConsumer(
            @PathVariable UUID consumerId, Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                bookingService.listByConsumer(consumerId, pageable, authentication).map(bookingMapper::toResponse)));
    }

    @GetMapping("/provider/{providerId}")
    @Operation(summary = "List a provider's bookings", description = "Paginated bookings for the "
            + "given provider's listings. Owning provider or ADMIN only.")
    public ResponseEntity<PagedResponse<BookingResponse>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                bookingService.listByProvider(providerId, pageable, authentication).map(bookingMapper::toResponse)));
    }

    /**
     * L29 (feature-expansion roadmap §5, Week 4): the booking write is one of
     * the three high-impact public write endpoints covered by an independent
     * named rate-limiter instance. timeout-duration 0 (application.yml) — a
     * limited call fails fast with 429 RL-001 instead of queueing.
     */
    @PostMapping
    @RateLimiter(name = "bookingCreate")
    @Operation(summary = "Create a booking request",
            description = "Books a stay window for a listing. The nightly total is derived server-side "
                    + "from the effective price — the client never supplies pricing.")
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request,
                                                  Authentication authentication) {
        UUID consumerId = currentUserProvider.getCurrentUserId(authentication);
        Booking booking = bookingService.create(
                consumerId, request.listingId(), request.startsAt(), request.endsAt(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingMapper.toResponse(booking));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm a booking (provider)", description = "Accepts a PENDING booking; "
            + "frees the slot window and starts the payment intent flow.")
    public ResponseEntity<BookingResponse> confirm(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(bookingMapper.toResponse(bookingService.confirm(id, authentication)));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Complete a booking (provider)", description = "Marks a confirmed stay "
            + "completed — the state from which the consumer may review.")
    public ResponseEntity<BookingResponse> complete(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(bookingMapper.toResponse(bookingService.complete(id, authentication)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a booking", description = "Consumer or provider cancellation; "
            + "frees the slot window and triggers the payment refund path (L19).")
    public ResponseEntity<BookingResponse> cancel(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(bookingMapper.toResponse(bookingService.cancel(id, authentication)));
    }

    /**
     * L29: request contract for {@link #create}. The stay window is
     * [startsAt, endsAt) with an exclusive end — the L27/L26 half-open
     * interval convention.
     */
    @Schema(description = "Booking request: a listing id plus the stay window; pricing is derived server-side")
    public record CreateBookingRequest(
            @Schema(description = "The listing to book", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @NotNull UUID listingId,
            @Schema(description = "Stay start (inclusive), ISO-8601 instant",
                    example = "2026-10-01T14:00:00Z")
            @NotNull Instant startsAt,
            @Schema(description = "Stay end (EXCLUSIVE — the checkout morning is not a priced night), "
                    + "ISO-8601 instant", example = "2026-10-04T10:00:00Z")
            @NotNull Instant endsAt,
            @Schema(description = "Optional free-text note for the provider",
                    example = "Arriving late, please keep the key in the lockbox")
            String notes
    ) {
    }
}
