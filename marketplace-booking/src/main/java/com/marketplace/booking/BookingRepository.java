package com.marketplace.booking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking>, RevisionRepository<Booking, UUID, Integer> {

    Page<Booking> findByConsumerId(UUID consumerId, Pageable pageable);

    Page<Booking> findByProviderId(UUID providerId, Pageable pageable);

    Page<Booking> findByListingId(UUID listingId, Pageable pageable);

    Page<Booking> findByStatus(BookingStatus status, Pageable pageable);

    Page<BookingSummaryView> findAllByConsumerId(UUID consumerId, Pageable pageable);

    Page<BookingSummaryView> findAllByProviderId(UUID providerId, Pageable pageable);

    Page<BookingSummaryView> findAllByStatus(BookingStatus status, Pageable pageable);

    /**
     * L25 (feature-expansion roadmap §5): the provider's COMPLETED-booking
     * count inside {@code [from, to)} — bookings whose {@code startsAt}
     * lies in the window (the house exclusive-end convention, the same one
     * the slot stats use). Backs {@code BookingStatsPort} through
     * {@code BookingStatsAdapter}.
     */
    long countByProviderIdAndStatusAndStartsAtGreaterThanEqualAndStartsAtLessThan(
            UUID providerId, BookingStatus status, Instant from, Instant to);
}