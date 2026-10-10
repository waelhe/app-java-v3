package com.marketplace.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);

    /**
     * A-11: the history page's items in ONE hop (the grouped-by-order map
     * the service assembles the page's view assemblies from).
     */
    List<OrderItem> findByOrderIdIn(List<UUID> orderIds);
}
