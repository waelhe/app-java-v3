package com.marketplace.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
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
        Instant cutoff = event.getDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Booking> stale = bookingRepository.findAll(
                BookingSpecifications.hasStatus(BookingStatus.PENDING)
                        .and(BookingSpecifications.createdBefore(cutoff))
        );
        stale.forEach(b -> bookingService.autoCancel(b.getId()));
    }

}
