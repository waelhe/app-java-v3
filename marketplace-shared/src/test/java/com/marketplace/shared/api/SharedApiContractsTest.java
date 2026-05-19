package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SharedApiContractsTest {

    @Test
    void bookingInfo_shouldValidateAndAuthorizeParticipant() {
        UUID providerId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        BookingInfo info = new BookingInfo(providerId, consumerId, "CONFIRMED", 1500L, "SAR", Instant.now(), Instant.now());

        assertDoesNotThrow(() -> info.requireStatus("CONFIRMED", "create payment intent"));
        assertThrows(IllegalStateException.class, () -> info.requireStatus("PENDING", "create payment intent"));
        assertDoesNotThrow(() -> info.requireStatusNot("CANCELLED", "create payment intent"));
        assertThrows(IllegalStateException.class, () -> info.requireStatusNot("CONFIRMED", "create payment intent"));
        assertDoesNotThrow(() -> info.requireParticipant(providerId));
        assertDoesNotThrow(() -> info.requireParticipant(consumerId));
        assertThrows(Exception.class, () -> info.requireParticipant(UUID.randomUUID()));
    }

    @Test
    void bookingInfo_shouldRejectInvalidPriceOrCurrency() {
        UUID providerId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();

        assertThrows(IllegalStateException.class,
                () -> new BookingInfo(providerId, consumerId, "CONFIRMED", 0L, "SAR", Instant.now(), Instant.now()));
        assertThrows(IllegalStateException.class,
                () -> new BookingInfo(providerId, consumerId, "CONFIRMED", 100L, " ", Instant.now(), Instant.now()));
    }

    @Test
    void pagedResponse_shouldMapPageMetadata() {
        var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 5);
        PagedResponse<String> response = PagedResponse.of(page);

        assertEquals(2, response.content().size());
        assertEquals(1, response.pageNumber());
        assertEquals(2, response.pageSize());
        assertEquals(5, response.totalElements());
        assertEquals(3, response.totalPages());
    }

    @Test
    void resourceNotFoundException_shouldBuildCanonicalMessage() {
        UUID id = UUID.randomUUID();
        ResourceNotFoundException ex = new ResourceNotFoundException("Booking", id);
        assertTrue(ex.getMessage().contains("Booking not found"));
        assertTrue(ex.getMessage().contains(id.toString()));
    }

    @Test
    void recordsAndConstants_shouldExposeExpectedValues() {
        assertEquals("/api/v1", ApiConstants.API_V1);
        assertNotNull(new BookingCreatedEvent(UUID.randomUUID()).bookingId());
        assertNotNull(new ListingCreatedEvent(UUID.randomUUID()).listingId());
        assertNotNull(new PaymentStateChangedEvent(UUID.randomUUID(), "COMPLETED").paymentIntentId());
        assertNotNull(new ReviewCreatedEvent(UUID.randomUUID()).reviewId());

        BookingSummary booking = new BookingSummary(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 1000L, "SAR", Instant.now(), Instant.now());
        assertEquals("CONFIRMED", booking.status());

        PaymentSummary payment = new PaymentSummary(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1000L, "SAR", "SUCCEEDED", Instant.now(), Instant.now());
        assertEquals("SUCCEEDED", payment.status());

        ProviderSummary provider = new ProviderSummary(UUID.randomUUID(), "Provider", "ACTIVE");
        assertEquals("Provider", provider.displayName());

        ListingSummary listing = new ListingSummary(UUID.randomUUID(), "Title", "cat", BigDecimal.valueOf(1000), "Provider");
        assertEquals("Title", listing.title());

        ProviderListingSummary pls = new ProviderListingSummary(UUID.randomUUID(), "Name", "cat",
                BigDecimal.valueOf(1000), UUID.randomUUID(), "ACTIVE", Instant.now(), Instant.now());
        assertEquals("Name", pls.title());

        UserSummary user = new UserSummary(UUID.randomUUID(), "email@example.com", "User", "CONSUMER", Instant.now(), Instant.now());
        assertEquals("CONSUMER", user.role());

        PaymentIntentDetails details = new PaymentIntentDetails(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CREATED");
        assertEquals("CREATED", details.status());

        ErrorResponse error = new ErrorResponse(400, "Bad Request", "Validation failed", "/api/v1/test");
        assertEquals(400, error.status());
        assertTrue(error.fieldErrors().isEmpty());
    }
}
