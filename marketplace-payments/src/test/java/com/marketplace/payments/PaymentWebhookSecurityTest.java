package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentWebhookSecurityTest {

    @Test
    void validSignaturePasses() {
        var security = new PaymentWebhookSecurity("my-secret");
        assertDoesNotThrow(() -> security.validateSignature("my-secret"));
    }

    @Test
    void invalidSignatureThrows() {
        var security = new PaymentWebhookSecurity("my-secret");
        assertThrows(AccessDeniedException.class, () -> security.validateSignature("wrong"));
    }

    @Test
    void nullSignatureThrows() {
        var security = new PaymentWebhookSecurity("my-secret");
        assertThrows(AccessDeniedException.class, () -> security.validateSignature(null));
    }

    @Test
    void blankSecretSkipsValidation() {
        var security = new PaymentWebhookSecurity("");
        assertDoesNotThrow(() -> security.validateSignature(null));
    }

    @Test
    void nullSecretSkipsValidation() {
        var security = new PaymentWebhookSecurity(null);
        assertDoesNotThrow(() -> security.validateSignature(null));
    }
}
