package com.marketplace.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class BookingExpirationService {

    private static final Logger log = LoggerFactory.getLogger(BookingExpirationService.class);

    private final BookingService bookingService;
    private final BookingRepository bookingRepository;

    public BookingExpirationService(BookingService bookingService, BookingRepository bookingRepository) {
        this.bookingService = bookingService;
        this.bookingRepository = bookingRepository;
    }

    @ApplicationModuleListener
    public void onDayHasPassed(DayHasPassed event) {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Booking> stale = bookingRepository.findAll(
                BookingSpecifications.hasStatus(BookingStatus.PENDING)
                        .and(BookingSpecifications.createdBefore(cutoff))
        );
        stale.forEach(b -> {
            try {
                bookingService.autoCancel(b.getId());
            } catch (Exception e) {
                log.error("Failed to expire booking: {}", b.getId(), e);
            }
        });
    }

}
