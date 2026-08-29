package com.marketplace.booking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = BookingExpirationServiceTest.TestConfig.class)
class BookingExpirationServiceTest {

    @Configuration
    static class TestConfig {
        @Bean
        BookingExpirationService bookingExpirationService(
                BookingService bookingService,
                BookingRepository bookingRepository) {
            return new BookingExpirationService(bookingService, bookingRepository);
        }
    }

    @MockitoBean
    BookingService bookingService;

    @MockitoBean
    BookingRepository bookingRepository;

    @Autowired
    BookingExpirationService listener;

    @Test
    void onDayHasPassed_cancelsStaleBookings() {
        DayHasPassed event = DayHasPassed.of(LocalDate.now());
        Booking staleBooking = mock(Booking.class);
        UUID bookingId = UUID.randomUUID();
        when(staleBooking.getId()).thenReturn(bookingId);
        when(bookingRepository.findAll(any(Specification.class))).thenReturn(Collections.singletonList(staleBooking));

        listener.onDayHasPassed(event);

        verify(bookingService).autoCancel(bookingId);
    }

    @Test
    void onDayHasPassed_propagatesException() {
        DayHasPassed event = DayHasPassed.of(LocalDate.now());
        when(bookingRepository.findAll(any(Specification.class))).thenThrow(new RuntimeException("Database error"));

        assertThrows(RuntimeException.class,
                () -> listener.onDayHasPassed(event));
    }

    @Test
    void onDayHasPassed_usesApplicationModuleListenerAnnotation() throws NoSuchMethodException {
        var method = BookingExpirationService.class.getMethod(
                "onDayHasPassed", DayHasPassed.class);
        ApplicationModuleListener ann = method.getAnnotation(ApplicationModuleListener.class);
        assertNotNull(ann);
    }
}
