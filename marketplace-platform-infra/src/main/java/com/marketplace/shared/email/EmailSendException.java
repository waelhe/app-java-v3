package com.marketplace.shared.email;

/**
 * Thrown when an email cannot be sent.
 */
public class EmailSendException extends RuntimeException {

    public EmailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
