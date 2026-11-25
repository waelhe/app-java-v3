package com.marketplace.pricing.spi;

import java.util.List;

/**
 * P3 (the canonical Saleor plan §5-P3 — the voucher mutation roots): the
 * mutation outcome — the domain mapping of Saleor's payload shape (the
 * registered "Mutation payload errors" ADAPT divergence). An empty error
 * list IS the success verdict.
 */
public record VoucherMutationResult(VoucherView voucher, List<VoucherMutationError> errors) {

    /** The success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }

    /** The success factory. */
    public static VoucherMutationResult success(VoucherView voucher) {
        return new VoucherMutationResult(voucher, List.of());
    }

    /** The failure factory — the domain error entries, no projection. */
    public static VoucherMutationResult failure(List<VoucherMutationError> errors) {
        return new VoucherMutationResult(null, List.copyOf(errors));
    }
}
