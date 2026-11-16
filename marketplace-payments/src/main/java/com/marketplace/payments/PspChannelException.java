package com.marketplace.payments;

/**
 * Wraps provider SDK failures (checked {@code StripeException} and friends)
 * into the house runtime convention. Retry/resilience annotations upstream
 * operate on runtime exceptions, so the channel must not leak checked SDK
 * types through the seam.
 */
class PspChannelException extends RuntimeException {

    PspChannelException(String message, Throwable cause) {
        super(message, cause);
    }

    PspChannelException(String message) {
        super(message);
    }
}
