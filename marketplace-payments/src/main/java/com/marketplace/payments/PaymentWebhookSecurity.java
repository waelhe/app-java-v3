package com.marketplace.payments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class PaymentWebhookSecurity {

    private final String sharedSecret;

    public PaymentWebhookSecurity(@Value("${marketplace.payments.webhook.shared-secret:}") String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    public void validateSignature(String providedSignature) {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            return;
        }
        if (providedSignature == null || !sharedSecret.equals(providedSignature)) {
            throw new AccessDeniedException("Invalid webhook signature");
        }
    }
}
