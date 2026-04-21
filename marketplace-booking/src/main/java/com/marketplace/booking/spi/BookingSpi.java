package com.marketplace.booking.spi;

import com.marketplace.shared.api.BookingSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;

/**
 * SPI for cross-module access to booking operations.
 * Admin and future modules depend on this interface, not on BookingService directly.
 */
@NamedInterface("booking-spi")
public interface BookingSpi {

    Page<BookingSummary> listAllSummaries(Pageable pageable);

    Page<BookingSummary> listByStatusSummary(String status, Pageable pageable);
}
