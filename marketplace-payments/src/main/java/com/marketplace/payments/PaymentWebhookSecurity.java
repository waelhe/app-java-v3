package com.marketplace.payments;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public class PaymentWebhookSecurity {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookSecurity.class);

    private final String sharedSecret;

    public PaymentWebhookSecurity(@Value("${marketplace.payments.webhook.shared-secret:}") String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    @PostConstruct
    void warnIfSecretNotConfigured() {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            log.warn("marketplace.payments.webhook.shared-secret is not configured -- " +
                    "webhook requests will be REJECTED. Set this property in production.");
        }
    }

    public void validateSignature(String payload, String providedSignature) {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new AccessDeniedException("Webhook shared-secret not configured");
        }
        String expected = computeSignature(payload);
        if (providedSignature == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException("Invalid webhook signature");
        }
    }

    String computeSignature(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new AccessDeniedException("Failed to compute webhook signature", e);
        }
    }
}
