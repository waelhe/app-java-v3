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

    // ---- The V226 dispatch-queue reads (the operational board) ----

    /** The board's per-state counts — one grouped query, never N+1. */
    @Query("select r.status as state, count(r) from DeliveryRequest r"
            + " where r.status in (com.marketplace.delivery.DeliveryRequestStatus.READY_FOR_DISPATCH,"
            + " com.marketplace.delivery.DeliveryRequestStatus.ASSIGNED,"
            + " com.marketplace.delivery.DeliveryRequestStatus.PICKED_UP,"
            + " com.marketplace.delivery.DeliveryRequestStatus.IN_TRANSIT,"
            + " com.marketplace.delivery.DeliveryRequestStatus.AWAITING_PAYMENT,"
            + " com.marketplace.delivery.DeliveryRequestStatus.PAYMENT_FAILED)"
            + " group by r.status")
    java.util.List<Object[]> countActiveByStatus();

    /**
     * The at-risk promises: non-terminal legs whose promised window
     * (creation + the route's duration × the configured multiplier) has
     * already passed — the dispatcher's exception queue.
     */
    @Query("select count(r) from DeliveryRequest r"
            + " where r.status in (com.marketplace.delivery.DeliveryRequestStatus.ASSIGNED,"
            + " com.marketplace.delivery.DeliveryRequestStatus.PICKED_UP,"
            + " com.marketplace.delivery.DeliveryRequestStatus.IN_TRANSIT)"
            + " and r.createdAt + (r.routeDurationSeconds * :multiplier) < :now")
    long countAtRisk(@Param("multiplier") long multiplier, @Param("now") java.time.Instant now);

    /** The reconciliation window's legs (terminal and in-flight alike). */
    @Query("select r from DeliveryRequest r where r.createdAt >= :from and r.createdAt < :to"
            + " order by r.createdAt asc, r.id asc")
    java.util.List<DeliveryRequest> findByCreatedAtBetween(@Param("from") java.time.Instant from,
                                                           @Param("to") java.time.Instant to);
}
