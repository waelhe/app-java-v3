package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String displayName,
        String role,
        Instant createdAt,
        Instant updatedAt,
        /**
         * I7/V46 (W1 addition): the account's pseudonymization marker —
         * {@code null} = a live account. Lifted into the cross-module
         * summary so every read surface can honour the marker (the reviews
         * module's reviewer identity, yelp plan §4.5) without reaching into
         * the identity module.
         */
        Instant pseudonymizedAt
) {

    /**
     * I7 (account-pseudonymization-plan §5-أ step 3): the neutral
     * "former member" label rendered at the response level — measured
     * equal to the identity module's own
     * {@code UserService.FORMER_MEMBER_LABEL} (both surfaces must
     * answer the same word for the same account).
     */
    public static final String FORMER_MEMBER_LABEL = "Former member";

    /**
     * The display name every public surface may render: the neutral label
     * for a pseudonymized account (the marker rules, never the stored
     * value), the profile name else, then the email, then the generic
     * fallback — the same hierarchical fallback the house's
     * {@code ProviderNameResolver} applies to provider names.
     */
    public String publicDisplayName() {
        if (pseudonymizedAt != null) {
            return FORMER_MEMBER_LABEL;
        }
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        return "User";
    }
}

