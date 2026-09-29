package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(membership.getVerificationState()).isEqualTo(MembershipVerificationState.UNVERIFIED);
        assertThat(membership.getMemberSince()).isEqualTo(FIXED);
    }

    @Test
    void theVocabularyCarriesTheExplicitTrustLifecycle() {
        assertThat(MembershipVerificationState.values())
                .containsExactly(MembershipVerificationState.UNVERIFIED,
                        MembershipVerificationState.PENDING,
                        MembershipVerificationState.VERIFIED,
                        MembershipVerificationState.REJECTED);
    }

    @Test
    void pendingVerification_canBeApprovedOrRejected_andRecordsTheEntityTransition() {
        NeighborhoodMembership approved = NeighborhoodMembership.join(UUID.randomUUID(), UUID.randomUUID(), clock);
        approved.requestVerification();
        approved.approveVerification();
        assertThat(approved.getVerificationState()).isEqualTo(MembershipVerificationState.VERIFIED);

        NeighborhoodMembership rejected = NeighborhoodMembership.join(UUID.randomUUID(), UUID.randomUUID(), clock);
        rejected.requestVerification();
        rejected.rejectVerification();
        assertThat(rejected.getVerificationState()).isEqualTo(MembershipVerificationState.REJECTED);
        assertThat(rejected.mayUseCommunityWrites()).isFalse();
        assertThatThrownBy(rejected::approveVerification).isInstanceOf(IllegalStateException.class);
    }
}
