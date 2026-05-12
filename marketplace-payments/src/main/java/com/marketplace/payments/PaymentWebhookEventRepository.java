package com.marketplace.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, UUID> {
    boolean existsByProviderAndExternalEventId(String provider, String externalEventId);
}
