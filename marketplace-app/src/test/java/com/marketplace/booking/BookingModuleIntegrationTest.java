package com.marketplace.booking;

import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;
import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.BookingConfirmedEvent;
import com.marketplace.shared.api.EffectivePricePort;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class BookingModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    ListingPriceProvider listingPriceProvider;

    @MockitoBean
    AvailabilityPort availabilityPort;

    /**
     * L26: the effective-price seam — PricingService (the port's only
     * implementation) lives in the pricing module, which is NOT part of
     * the booking slice (booking's direct dependencies are shared-only).
     */
    @MockitoBean
    EffectivePricePort effectivePricePort;

    @MockitoBean
    PaymentIntentLookupPort paymentIntentLookupPort;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void listAllSummaries_returnsEmptyPage() {
        var page = bookingService.listAllSummaries(Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }

    @Test
    void listByStatus_returnsEmptyPage() {
        var page = bookingService.listByStatus(BookingStatus.PENDING, Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }

    /**
     * A-03 (official-compliance plan 0.6 — the unit's measured gate,
     * "PublishedEvents test"): the official Modulith test API
     * (reference/events.html — "Spring Modulith's @ApplicationModuleTest
     * enables the ability to get a PublishedEvents instance injected into
     * the test method to verify a particular set of events has been
     * published during the course of the business operation under test")
     * pins the once-dead event's publication on the real transactional path:
     * autoConfirm is the payment-driven confirm site (the plain, unadorned
     * one — no method-security or resilience aspect rides it), publishing
     * the SAME event type and payload as the manual confirm path
     * (BookingConfirmedEvent(bookingId)) inside its business transaction.
     * The delivery consumer (the notifications listener this unit landed)
     * lives in another module's slice by design — the registry journey and
     * the notification delivery are integration-tested at the app level
     * (CI judges, disabledWithoutDocker here).
     */
    @Test
    void autoConfirmPublishesBookingConfirmedEvent_a03(PublishedEvents events) {
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var booking = Booking.create(consumerId, providerId, listingId, 5000L,
                java.time.Instant.parse("2026-10-01T10:00:00Z"),
                java.time.Instant.parse("2026-10-01T11:00:00Z"), "a03 notes");
        var saved = bookingRepository.save(booking);

        bookingService.autoConfirm(saved.getId());

        var matching = events.ofType(BookingConfirmedEvent.class)
                .matchingValue(BookingConfirmedEvent::bookingId, saved.getId());
        assertThat(matching).hasSize(1);
    }
}
