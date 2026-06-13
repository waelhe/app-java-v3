package com.marketplace.payments;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class PaymentWebhookSecurity {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookSecurity.class);

    private final String sharedSecret;

    public PaymentWebhookSecurity(@Value("${marketplace.payments.webhook.shared-secret:}") String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    @PostConstruct
    void warnIfSecretNotConfigured() {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            log.warn("marketplace.payments.webhook.shared-secret is not configured — " +
                    "webhook signature validation is DISABLED. Set this property in production.");
        }
    }

    public void validateSignature(String providedSignature) {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new AccessDeniedException("Webhook shared-secret not configured");
        }
        if (providedSignature == null || !sharedSecret.equals(providedSignature)) {
            throw new AccessDeniedException("Invalid webhook signature");
        }
    }
}
