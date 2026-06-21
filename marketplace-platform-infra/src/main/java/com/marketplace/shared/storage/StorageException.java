package com.marketplace.shared.storage;

import org.springframework.stereotype.Component;

/**
 * Exception thrown when a storage operation fails.
 *
 * <p>Follows the same pattern as {@code EmailSendException} -- a simple
 * RuntimeException subclass for infrastructure-level failures.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
