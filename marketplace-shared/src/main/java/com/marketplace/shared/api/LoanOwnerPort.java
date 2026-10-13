package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 8 (plan D-09, ADR-0004): the loan's owner seam — the single fact
 * the ledger's LOAN-origin settlement needs (who to credit the fee). The
 * {@code OrderSellerPort} twin verbatim: the loan stores its owner at
 * request time (denormalized from the product), the ledger reads it
 * through this contract and never imports the lending module.
 */
public interface LoanOwnerPort {

    /**
     * Resolves the loan's owner.
     *
     * @param loanId the loan's id
     * @return the owner's provider id (stored at request time)
     * @throws com.marketplace.shared.api.ResourceNotFoundException when the
     *         loan does not exist
     */
    UUID ownerOf(UUID loanId);
}
