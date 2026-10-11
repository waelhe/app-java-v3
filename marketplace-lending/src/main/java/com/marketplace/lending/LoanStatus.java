package com.marketplace.lending;

/**
 * Stage 8 (ADR-0004): the loan machine's states. Persisted as the V174
 * CHECK's string membership set ({@code @Enumerated(STRING)} — the
 * OrderStatus discipline verbatim). The live set
 * ({@code APPROVED/ACTIVE/RETURN_REQUESTED}) is what the period's
 * exclusivity constraint guards.
 */
public enum LoanStatus {
    REQUESTED,
    APPROVED,
    ACTIVE,
    RETURN_REQUESTED,
    RETURNED,
    CLOSED,
    DECLINED,
    CANCELLED,
    DISPUTED
}
