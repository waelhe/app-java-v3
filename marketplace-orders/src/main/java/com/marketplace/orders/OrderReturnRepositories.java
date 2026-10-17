package com.marketplace.orders;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface OrderReturnRepository extends JpaRepository<OrderReturn, UUID> {

    Optional<OrderReturn> findById(UUID id);

    Page<OrderReturn> findByConsumerIdOrderByCreatedAtDesc(UUID consumerId, Pageable pageable);

    Page<OrderReturn> findByOrderIdOrderByCreatedAtDesc(UUID orderId, Pageable pageable);

    Page<OrderReturn> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<OrderReturn> findByOrderIdAndStatusIn(UUID orderId, List<OrderReturn.ReturnStatus> statuses);

    long countByOrderId(UUID orderId);
}

interface OrderReturnLineRepository extends JpaRepository<OrderReturnLine, UUID> {

    List<OrderReturnLine> findByOrderReturnId(UUID orderReturnId);

    List<OrderReturnLine> findByOrderItemId(UUID orderItemId);
}
