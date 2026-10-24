package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the category mutation roots): one
 * domain validation outcome — the transport boundary maps this onto the
 * Saleor payload error object ({@code field}, {@code message},
 * {@code code}).
 */
public record CategoryMutationError(String field, CategoryMutationErrorCode code, String message) {
}
