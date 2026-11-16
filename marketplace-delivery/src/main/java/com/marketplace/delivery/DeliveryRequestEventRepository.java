package com.marketplace.delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DeliveryRequestEventRepository extends JpaRepository<DeliveryRequestEvent, UUID> {
}
