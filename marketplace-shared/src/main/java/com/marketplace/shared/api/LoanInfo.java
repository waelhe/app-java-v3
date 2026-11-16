package com.marketplace.shared.api;

import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

/**
 * ADR-0009 (plan D-09 closure — ADR-0004's dispute seam): the loan's party
 * facts — the {@code BookingInfo} twin verbatim. The disputes module gates
 * a loan-dispute's visibility and opening on these facts and never imports
 * the lending module.
 */
public record LoanInfo(UUID loanId, UUID borrowerId, UUID ownerId) {

    /**
     * The participation gate — the borrower or the owner; anyone else is
     * denied (the {@code BookingInfo.requireParticipant} twin verbatim).
     */
    public void requireParty(UUID userId) {
        if (!borrowerId.equals(userId) && !ownerId.equals(userId)) {
            throw new AccessDeniedException("You are not a party in this loan");
        }
    }
}
