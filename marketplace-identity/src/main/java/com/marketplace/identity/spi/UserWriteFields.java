package com.marketplace.identity.spi;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * writable field set the customer/staff management mutations carry —
 * first/last name compose the platform's single display-name column
 * (the recorded ADAPT divergence), email/activation ride the standing
 * two-store identity machinery.
 */
public record UserWriteFields(String email, String firstName, String lastName, Boolean isActive) {

    /** The display-name composition — the platform's single name column. */
    public String displayName() {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String composed = (first + " " + last).trim();
        return composed.isEmpty() ? null : composed;
    }
}
