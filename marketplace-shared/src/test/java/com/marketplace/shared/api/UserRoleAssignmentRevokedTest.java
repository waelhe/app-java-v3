package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 (the unified plan §10, D-03) — the multi-role revocation fact's
 * value semantics: the record the identity module's revoke command
 * publishes and the {@code AccountStatusSessionInvalidator} consumes (the
 * R8 consumer shape). The payload is identifiers and stored names only —
 * the {@code UserRoleChanged} String-vocabulary rule: no identity-domain
 * type crosses the boundary, so the consumer never needs the identity
 * module on its classpath.
 */
class UserRoleAssignmentRevokedTest {

    @Test
    void carriesTheFullConsumerPayload() {
        UUID userId = UUID.randomUUID();
        UserRoleAssignmentRevoked event =
                new UserRoleAssignmentRevoked(userId, "member@example.com", "PROVIDER");
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.username()).isEqualTo("member@example.com");
        assertThat(event.role()).isEqualTo("PROVIDER");
        assertThat(event.toString()).contains("PROVIDER").contains("member@example.com");
    }

    @Test
    void recordSemantics_twoFactsAboutTheSameRevocationAreEqual() {
        UUID userId = UUID.randomUUID();
        UserRoleAssignmentRevoked a =
                new UserRoleAssignmentRevoked(userId, "member@example.com", "ADMIN");
        UserRoleAssignmentRevoked b =
                new UserRoleAssignmentRevoked(userId, "member@example.com", "ADMIN");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);

        UserRoleAssignmentRevoked differentRole =
                new UserRoleAssignmentRevoked(userId, "member@example.com", "CONSUMER");
        assertThat(a).isNotEqualTo(differentRole);
    }
}
