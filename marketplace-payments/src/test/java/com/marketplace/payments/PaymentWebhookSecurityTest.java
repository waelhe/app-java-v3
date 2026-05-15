package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;

class PaymentWebhookSecurityTest {

    @Test
    void validateSignature_matchingSecret_passes() {
        PaymentWebhookSecurity security = new PaymentWebhookSecurity("secret123");
        assertDoesNotThrow(() -> security.validateSignature("secret123"));
    }

    @Test
    void validateSignature_wrongSecret_throws() {
        PaymentWebhookSecurity security = new PaymentWebhookSecurity("secret123");
        assertThrows(AccessDeniedException.class, () -> security.validateSignature("wrong"));
    }

    @Test
    void validateSignature_nullSignatureWithSecret_throws() {
        PaymentWebhookSecurity security = new PaymentWebhookSecurity("secret123");
        assertThrows(AccessDeniedException.class, () -> security.validateSignature(null));
    }

    @Test
    void validateSignature_blankSecret_skipsValidation() {
        PaymentWebhookSecurity security = new PaymentWebhookSecurity("");
        assertDoesNotThrow(() -> security.validateSignature(null));
    }

    @Test
    void validateSignature_nullSecret_skipsValidation() {
        PaymentWebhookSecurity security = new PaymentWebhookSecurity(null);
        assertDoesNotThrow(() -> security.validateSignature(null));
    }
}
