package com.marketplace.orders;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * A-11: the buyer's own order history, newest-first (the D-N5 complete
     * ordering key — {@code placedAt DESC, id DESC} — so pages neither
     * shuffle nor shift).
     */
    Page<Order> findByConsumerIdOrderByPlacedAtDescIdDesc(UUID consumerId, Pageable pageable);
}
