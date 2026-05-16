package com.marketplace.booking;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class BookingExpirationService {

    private final BookingRepository bookingRepository;

    public BookingExpirationService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @ApplicationModuleListener
    public void onDayHasPassed(DayHasPassed event) {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        List<Booking> stale = bookingRepository.findAll(
                BookingSpecifications.hasStatus(BookingStatus.PENDING)
                        .and(BookingSpecifications.createdBefore(cutoff))
        );
        stale.forEach(Booking::cancel);
        bookingRepository.saveAll(stale);
    }

}
