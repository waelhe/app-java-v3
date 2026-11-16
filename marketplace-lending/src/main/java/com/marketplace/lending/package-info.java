/**
 * Stage 8 (plan D-09, ADR-0004): the lending workflow — the offer
 * projection (the owner's own terms) and the loan machine (request →
 * approve → pay via the existing engine → handover → return → close).
 * Cross-module facts ride the shared contracts: the product's identity
 * and state through {@code ProductPricingPort}, the fee's money through
 * {@code LoanPaymentPort} (the LOAN origin on the EXISTING engine), and
 * the ledger's credit fact through {@code LoanOwnerPort}.
 */
@org.springframework.modulith.NamedInterface("lending")
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa"}
)
package com.marketplace.lending;
