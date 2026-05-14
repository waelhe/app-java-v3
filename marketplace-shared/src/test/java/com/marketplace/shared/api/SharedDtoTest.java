package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SearchCriteriaTest {

    @Test
    void createWithAllFields() {
        var criteria = new SearchCriteria("plumber", "services", BigDecimal.valueOf(100), BigDecimal.valueOf(500));
        assertEquals("plumber", criteria.query());
        assertEquals("services", criteria.category());
        assertEquals(BigDecimal.valueOf(100), criteria.minPrice());
        assertEquals(BigDecimal.valueOf(500), criteria.maxPrice());
    }

    @Test
    void createWithNullFields() {
        var criteria = new SearchCriteria(null, null, null, null);
        assertNull(criteria.query());
        assertNull(criteria.category());
        assertNull(criteria.minPrice());
        assertNull(criteria.maxPrice());
    }
}

class UserSummaryTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var summary = new UserSummary(id, "test@example.com", "John Doe", "PROVIDER", now, now);
        assertEquals(id, summary.id());
        assertEquals("test@example.com", summary.email());
        assertEquals("John Doe", summary.displayName());
        assertEquals("PROVIDER", summary.role());
    }
}

class ListingSummaryTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var price = BigDecimal.valueOf(49.99);
        var summary = new ListingSummary(id, "Plumber", "services", price, "Provider A");
        assertEquals(id, summary.id());
        assertEquals("Plumber", summary.title());
        assertEquals("services", summary.category());
        assertEquals(price, summary.price());
        assertEquals("Provider A", summary.providerName());
    }
}

class BookingSummaryTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var summary = new BookingSummary(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ACTIVE", 5000L, "SAR", now, now);
        assertEquals(id, summary.id());
        assertEquals("ACTIVE", summary.status());
        assertEquals(5000L, summary.priceCents());
    }
}

class PaymentSummaryTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var summary = new PaymentSummary(id, UUID.randomUUID(), UUID.randomUUID(),
                10000L, "SAR", "COMPLETED", now, now);
        assertEquals(id, summary.id());
        assertEquals("COMPLETED", summary.status());
        assertEquals(10000L, summary.amountCents());
    }
}

class ProviderSummaryTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var summary = new ProviderSummary(id, "Provider A", "verified");
        assertEquals(id, summary.id());
        assertEquals("Provider A", summary.displayName());
        assertEquals("verified", summary.status());
    }
}

class BookingInfoTest {
    @Test
    void create() {
        var now = Instant.now();
        var info = new BookingInfo(UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 5000L, "SAR", now, now);
        assertNotNull(info.providerId());
        assertNotNull(info.consumerId());
        assertEquals("CONFIRMED", info.status());
        assertEquals(5000L, info.priceCents());
    }

    @Test
    void constructor_throwsOnNullPrice() {
        var now = Instant.now();
        assertThrows(IllegalStateException.class, () ->
                new BookingInfo(UUID.randomUUID(), UUID.randomUUID(), "PENDING", null, "SAR", now, now));
    }

    @Test
    void constructor_throwsOnZeroPrice() {
        var now = Instant.now();
        assertThrows(IllegalStateException.class, () ->
                new BookingInfo(UUID.randomUUID(), UUID.randomUUID(), "PENDING", 0L, "SAR", now, now));
    }

    @Test
    void requireStatus_allowsMatching() {
        var now = Instant.now();
        var info = new BookingInfo(UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 5000L, "SAR", now, now);
        assertDoesNotThrow(() -> info.requireStatus("CONFIRMED", "test"));
    }

    @Test
    void requireStatus_throwsOnMismatch() {
        var now = Instant.now();
        var info = new BookingInfo(UUID.randomUUID(), UUID.randomUUID(), "PENDING", 5000L, "SAR", now, now);
        assertThrows(IllegalStateException.class, () -> info.requireStatus("CONFIRMED", "test"));
    }

    @Test
    void requireStatusNot_allowsDifferent() {
        var now = Instant.now();
        var info = new BookingInfo(UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 5000L, "SAR", now, now);
        assertDoesNotThrow(() -> info.requireStatusNot("PENDING", "test"));
    }

    @Test
    void requireStatusNot_throwsOnMatch() {
        var now = Instant.now();
        var info = new BookingInfo(UUID.randomUUID(), UUID.randomUUID(), "CANCELLED", 5000L, "SAR", now, now);
        assertThrows(IllegalStateException.class, () -> info.requireStatusNot("CANCELLED", "test"));
    }

    @Test
    void requireParticipant_allowsConsumer() {
        var consumerId = UUID.randomUUID();
        var now = Instant.now();
        var info = new BookingInfo(UUID.randomUUID(), consumerId, "CONFIRMED", 5000L, "SAR", now, now);
        assertDoesNotThrow(() -> info.requireParticipant(consumerId));
    }

    @Test
    void requireParticipant_allowsProvider() {
        var providerId = UUID.randomUUID();
        var now = Instant.now();
        var info = new BookingInfo(providerId, UUID.randomUUID(), "CONFIRMED", 5000L, "SAR", now, now);
        assertDoesNotThrow(() -> info.requireParticipant(providerId));
    }

    @Test
    void requireParticipant_throwsForNonParticipant() {
        var now = Instant.now();
        var info = new BookingInfo(UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 5000L, "SAR", now, now);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> info.requireParticipant(UUID.randomUUID()));
    }
}

class ProviderListingSummaryTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var summary = new ProviderListingSummary(id, "Test", "services", BigDecimal.TEN, id, "ACTIVE", now, now);
        assertEquals(id, summary.id());
        assertEquals("Test", summary.title());
        assertEquals("ACTIVE", summary.status());
    }
}

class PaymentIntentDetailsTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var details = new PaymentIntentDetails(id, UUID.randomUUID(), UUID.randomUUID(), "PENDING");
        assertEquals(id, details.paymentIntentId());
        assertEquals("PENDING", details.status());
    }
}

class ErrorResponseTest {
    @Test
    void create() {
        var response = new ErrorResponse(404, "Not found", "Resource not found", "/api/test");
        assertEquals(404, response.status());
        assertEquals("Not found", response.error());
        assertEquals("Resource not found", response.message());
        assertEquals("/api/test", response.path());
        assertNotNull(response.timestamp());
    }
}

class BookingCreatedEventTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var event = new BookingCreatedEvent(id);
        assertEquals(id, event.bookingId());
    }
}

class ListingCreatedEventTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var event = new ListingCreatedEvent(id);
        assertEquals(id, event.listingId());
    }
}

class PaymentStateChangedEventTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(id, "COMPLETED");
        assertEquals(id, event.paymentIntentId());
        assertEquals("COMPLETED", event.state());
    }
}

class ReviewCreatedEventTest {
    @Test
    void create() {
        var id = UUID.randomUUID();
        var event = new ReviewCreatedEvent(id);
        assertEquals(id, event.reviewId());
    }
}
