package com.marketplace.orders.spi;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of an ADMIN bulk-cancellation command. Individual domain refusals
 * are returned per order so one invalid row does not falsely imply that no
 * valid order was processed.
 */
public record OrderBulkCancelResult(int count, List<Failure> failures) {

    public OrderBulkCancelResult {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        failures = List.copyOf(failures);
    }

    public record Failure(UUID orderId, FailureKind kind, String message) {
    }

    public enum FailureKind {
        NOT_FOUND,
        CONFLICT,
        DUPLICATE
    }
}
