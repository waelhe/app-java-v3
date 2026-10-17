package com.marketplace.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * V223: the order price components' persistence port — public and in its own
 * file for the same proxy-contract reason the pricing module's repository
 * headers document (Spring Data's JDK dynamic proxies cannot carry a
 * non-public interface across the packaged application's split
 * classloading).
 */
public interface OrderPriceComponentRepository extends JpaRepository<OrderPriceComponent, UUID> {

    List<OrderPriceComponent> findByOrderId(UUID orderId);
}
