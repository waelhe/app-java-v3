package com.marketplace.delivery;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryQuoteRepository extends JpaRepository<DeliveryQuote, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from DeliveryQuote q where q.id = :id")
    Optional<DeliveryQuote> findByIdForUpdate(@Param("id") UUID id);
}
