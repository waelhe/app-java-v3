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

    /**
     * The order's already-DELIVERED legs (CodeRabbit agent findings, row
     * 16): the cancellation gate's delivered-goods guard — a full
     * cancellation releases ALL reserved inventory and requests a FULL
     * refund, so it must refuse whenever any package was physically
     * delivered (the buyer keeps delivered goods; only a future
     * partial-cancellation operation may refund the undelivered rest).
     */
    @Query("select count(r) from DeliveryRequest r where r.orderId = :orderId"
            + " and r.status = com.marketplace.delivery.DeliveryRequestStatus.DELIVERED")
    long countDeliveredByOrderId(@Param("orderId") UUID orderId);

    // ---- The V229 dispatch-queue reads (the operational board) ----

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
     *
     * <p>Native SQL: JPQL has no arithmetic between a temporal state-field
     * and a numeric expression (the JPA specification's arithmetic grammar
     * admits numeric operands only), so a per-row cutoff —
     * {@code created_at + route_duration_seconds × multiplier} — cannot be
     * written in JPQL; Hibernate 7 rejects it at repository boot with
     * {@code QueryCreationException} (the CI-red measured on this branch).
     * The interval shift is the official PostgreSQL form (PostgreSQL
     * Reference, Data Types › Date/Time Types › Interval Input:
     * {@code timestamp + interval}), the same house pattern as
     * {@code GeoLocationRepository}'s recursive-CTE native query. The status
     * literals match the entity's {@code @Enumerated(EnumType.STRING)}
     * column shape.
     */
    @Query(value = "select count(*) from delivery_requests r"
            + " where r.status in ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT')"
            + " and r.created_at + (r.route_duration_seconds * :multiplier * interval '1 second') < :now",
            nativeQuery = true)
    long countAtRisk(@Param("multiplier") long multiplier, @Param("now") java.time.Instant now);

    /** The reconciliation window's legs (terminal and in-flight alike). */
    @Query("select r from DeliveryRequest r where r.createdAt >= :from and r.createdAt < :to"
            + " order by r.createdAt asc, r.id asc")
    java.util.List<DeliveryRequest> findByCreatedAtBetween(@Param("from") java.time.Instant from,
                                                           @Param("to") java.time.Instant to);
}
