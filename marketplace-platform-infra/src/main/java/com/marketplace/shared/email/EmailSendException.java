package com.marketplace.shared.email;

/**
 * Thrown when an email cannot be sent.
 */
public class EmailSendException extends RuntimeException {

    public EmailSendException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The PII-free construction (the 2026-11-09 #807 round): the message
     * already carries the masked recipient and the scrubbed diagnostic
     * summary — no raw SMTP cause rides the chain into a caller's log
     * store.
     */
    public EmailSendException(String message) {
        super(message);
    }
}
