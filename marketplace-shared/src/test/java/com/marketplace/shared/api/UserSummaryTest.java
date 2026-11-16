package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1 (CodeRabbit r5 + greptile r6, adopted from the root): the public-name
 * contract of the cross-module user summary. The email tier is deliberately
 * ABSENT from {@link UserSummary#publicDisplayName()} — the method feeds
 * surfaces anonymous clients read (a published review's {@code reviewerName}),
 * and the login email is an address the account never chose to publish.
 * These tests pin that contract so the tier cannot silently return.
 */
class UserSummaryTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private UserSummary summary(String displayName, String email, Instant pseudonymizedAt) {
        return new UserSummary(UUID.randomUUID(), email, displayName, "USER", T, T, pseudonymizedAt);
    }

    @Test
    void publicDisplayName_rendersTheStoredDisplayName() {
        assertThat(summary("Sara", "sara@t.com", null).publicDisplayName()).isEqualTo("Sara");
    }

    @Test
    void publicDisplayName_blankDisplayNameAnswersTheGenericLabelNeverTheEmail() {
        // The privacy pin: a blank display name with a live email must render
        // the generic "User" label — the email is never a public name.
        assertThat(summary("   ", "secret@t.com", null).publicDisplayName()).isEqualTo("User");
        assertThat(summary(null, "secret@t.com", null).publicDisplayName()).isEqualTo("User");
    }

    @Test
    void publicDisplayName_noEmailAtAllAnswersTheGenericLabel() {
        assertThat(summary(null, null, null).publicDisplayName()).isEqualTo("User");
    }

    @Test
    void publicDisplayName_thePseudonymizationMarkerRulesOverEverything() {
        // The marker decides before any stored value — the I7 contract.
        assertThat(summary("Sara", "sara@t.com", T).publicDisplayName())
                .isEqualTo(UserSummary.FORMER_MEMBER_LABEL);
    }
}
