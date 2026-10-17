package com.marketplace.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface OrderPriceComponentRepository extends JpaRepository<OrderPriceComponent, UUID> {

    List<OrderPriceComponent> findByOrderId(UUID orderId);
}
