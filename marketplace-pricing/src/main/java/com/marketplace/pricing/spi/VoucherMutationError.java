package com.marketplace.pricing.spi;

/**
 * P3 (the canonical Saleor plan §5-P3 — the voucher mutation roots): one
 * domain validation outcome — the transport boundary maps this onto the
 * Saleor payload error object ({@code field}, {@code message},
 * {@code code}).
 */
public record VoucherMutationError(String field, VoucherMutationErrorCode code, String message) {
}
