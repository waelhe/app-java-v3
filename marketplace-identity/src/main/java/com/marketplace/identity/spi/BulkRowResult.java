package com.marketplace.identity.spi;

import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * per-row bulk outcome (Saleor's CustomerBulkResult shape) — a null
 * error code means the row succeeded.
 */
public record BulkRowResult(UUID userId, String errorCode, String errorMessage) {

    public boolean success() {
        return errorCode == null;
    }
}
