package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;

class PaymentWebhookSecurityTest {

    @Test
    void allowsWhenSecretIsBlank() {
        PaymentWebhookSecurity sec = new PaymentWebhookSecurity("");
        assertDoesNotThrow(() -> sec.validateSignature(null));
    }

    @Test
    void allowsWhenSignatureMatches() {
        PaymentWebhookSecurity sec = new PaymentWebhookSecurity("my-secret");
        assertDoesNotThrow(() -> sec.validateSignature("my-secret"));
    }

    @Test
    void rejectsWhenSignatureMismatches() {
        PaymentWebhookSecurity sec = new PaymentWebhookSecurity("my-secret");
        assertThrows(AccessDeniedException.class, () -> sec.validateSignature("wrong"));
    }

    @Test
    void rejectsWhenSignatureIsNull() {
        PaymentWebhookSecurity sec = new PaymentWebhookSecurity("my-secret");
        assertThrows(AccessDeniedException.class, () -> sec.validateSignature(null));
    }
}
