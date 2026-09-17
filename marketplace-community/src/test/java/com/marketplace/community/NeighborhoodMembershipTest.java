package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L41 — the anchor entity's factory contract: the join produces exactly
 * the honest insert shape (the stored-facts floor the V60 CHECKs back).
 */
class NeighborhoodMembershipTest {

    private static final Instant FIXED = Instant.parse("2026-09-17T09:30:00Z");
    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    @Test
    void joinFactory_setsEveryStoredFact() {
        UUID userId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        NeighborhoodMembership membership = NeighborhoodMembership.join(userId, locationId, clock);

        assertThat(membership.getId()).isNotNull();
        assertThat(membership.getUserId()).isEqualTo(userId);
        assertThat(membership.getLocationId()).isEqualTo(locationId);
        assertThat(membership.getVerificationState()).isEqualTo(MembershipVerificationState.SELF_DECLARED);
        assertThat(membership.getMemberSince()).isEqualTo(FIXED);
    }

    @Test
    void theVocabularyCarriesOnlyTheSelfDeclaredState() {
        // D-N3: VERIFIED is reserved behind G-N2 — the anchor's vocabulary
        // is exactly one value; the DB CHECK (V60) backs this floor for raw
        // writers too.
        assertThat(MembershipVerificationState.values())
                .containsExactly(MembershipVerificationState.SELF_DECLARED);
    }
}
