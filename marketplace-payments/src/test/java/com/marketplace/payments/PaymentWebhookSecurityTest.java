package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;

class PaymentWebhookSecurityTest {

    @Test
    void rejectsWhenSecretIsBlank() {
        PaymentWebhookSecurity sec = new PaymentWebhookSecurity("");
        assertThrows(AccessDeniedException.class, () -> sec.validateSignature(null));
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
