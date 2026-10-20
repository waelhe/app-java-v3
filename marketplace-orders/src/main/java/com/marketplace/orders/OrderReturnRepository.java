package com.marketplace.orders;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The V230 RMA lifecycle's aggregate repository (the governing one-interface-
 * per-file public-repository law: Spring Data's dynamic proxy cannot carry a
 * package-private interface across the packaged application's split class
 * loading — the measured #624/#628 boot failure «non-public interface is not
 * defined by the given loader», this branch's Full Integration red).
 */
public interface OrderReturnRepository extends JpaRepository<OrderReturn, UUID> {

    Optional<OrderReturn> findById(UUID id);

    /**
     * The completion edge's serialization lock (the review's Major finding):
     * two concurrent completes of the same return must not both observe the
     * pre-terminal state and both reach the refund port — the row lock
     * makes the second complete wait, then find COMPLETED and no-op read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OrderReturn r where r.id = :id")
    Optional<OrderReturn> findByIdForUpdate(@Param("id") UUID id);

    Page<OrderReturn> findByConsumerIdOrderByCreatedAtDescIdDesc(UUID consumerId, Pageable pageable);

    Page<OrderReturn> findByConsumerIdOrderByCreatedAtDesc(UUID consumerId, Pageable pageable);

    Page<OrderReturn> findByOrderIdOrderByCreatedAtDesc(UUID orderId, Pageable pageable);

    Page<OrderReturn> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    Page<OrderReturn> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<OrderReturn> findByOrderIdAndStatusIn(UUID orderId, List<OrderReturn.ReturnStatus> statuses);

    long countByOrderId(UUID orderId);
}
