package com.marketplace.booking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    Page<Booking> findByConsumerId(UUID consumerId, Pageable pageable);

    Page<Booking> findByProviderId(UUID providerId, Pageable pageable);

    Page<Booking> findByListingId(UUID listingId, Pageable pageable);

    Page<Booking> findByStatus(BookingStatus status, Pageable pageable);
}