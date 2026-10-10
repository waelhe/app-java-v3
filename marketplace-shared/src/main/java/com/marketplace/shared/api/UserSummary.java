package com.marketplace.shared.api;

import java.time.Instant;
import java.util.List;
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
        Instant pseudonymizedAt,
        /**
         * D-03 (community platform execution plan Stage 1): the account's
         * role SET — the roster surface answers the combinations, not just
         * the primary-role mirror. Never null/empty in the canonical form
         * (the 7-arg compatibility constructor fills it from the primary
         * role); the owning service populates the authoritative set.
         */
        List<String> roles
) {

    /** The pre-D-03 shape: the set is the primary role, the callers stay untouched. */
    public UserSummary(UUID id, String email, String displayName, String role,
                       Instant createdAt, Instant updatedAt, Instant pseudonymizedAt) {
        this(id, email, displayName, role, createdAt, updatedAt, pseudonymizedAt,
                role == null ? List.of() : List.of(role));
    }

    public UserSummary {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

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
     * value), the profile name else, then the generic fallback — the same
     * hierarchical fallback the house's {@code ProviderNameResolver} applies
     * to provider names.
     *
     * <p><b>The email tier is deliberately absent (CodeRabbit W1 r5 + greptile
     * W1 r6, adopted from the root):</b> this method feeds surfaces anonymous
     * clients read (the published review's {@code reviewerName}), and the
     * login email is an address the account never chose to publish. A
     * missing display name therefore renders the generic "User" label on
     * public surfaces — never the email. Authenticated surfaces that
     * legitimately need the email read it from their own facts (the user's
     * own profile, admin views), not from the public-name contract.
     */
    public String publicDisplayName() {
        if (pseudonymizedAt != null) {
            return FORMER_MEMBER_LABEL;
        }
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return "User";
    }
}

