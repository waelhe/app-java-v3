package com.marketplace.delivery;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryRequestRepository extends JpaRepository<DeliveryRequest, UUID> {

    Optional<DeliveryRequest> findByQuoteId(UUID quoteId);
    Page<DeliveryRequest> findByRequesterIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
    Page<DeliveryRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<DeliveryRequest> findByCourierUserIdOrderByCreatedAtDesc(UUID courierUserId, Pageable pageable);
    Page<DeliveryRequest> findByStatusOrderByCreatedAtAscIdAsc(
            DeliveryRequestStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DeliveryRequest r where r.id = :id")
    Optional<DeliveryRequest> findByIdForUpdate(@Param("id") UUID id);

    /** The V224 multi-leg reads: an order's legs and its still-active count. */
    java.util.List<DeliveryRequest> findByOrderIdOrderByCreatedAtAscIdAsc(UUID orderId);

    @Query("select count(r) from DeliveryRequest r where r.orderId = :orderId"
            + " and r.status in (com.marketplace.delivery.DeliveryRequestStatus.AWAITING_PAYMENT,"
            + " com.marketplace.delivery.DeliveryRequestStatus.PAYMENT_FAILED,"
            + " com.marketplace.delivery.DeliveryRequestStatus.READY_FOR_DISPATCH,"
            + " com.marketplace.delivery.DeliveryRequestStatus.ASSIGNED,"
            + " com.marketplace.delivery.DeliveryRequestStatus.PICKED_UP,"
            + " com.marketplace.delivery.DeliveryRequestStatus.IN_TRANSIT)")
    long countActiveByOrderId(@Param("orderId") UUID orderId);
}
