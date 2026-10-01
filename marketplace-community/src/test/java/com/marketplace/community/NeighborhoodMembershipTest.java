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
        // The #484 review round completed the lever in BOTH directions: a
        // REJECTED verdict IS re-admittable by the administrator (the recovery
        // lever for a verdict carried across a rejoin) — the refusal that
        // stands is the transition with no verdict to move (UNVERIFIED).
        NeighborhoodMembership unverified = NeighborhoodMembership.join(UUID.randomUUID(), UUID.randomUUID(), clock);
        assertThatThrownBy(unverified::approveVerification).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(unverified::rejectVerification).isInstanceOf(IllegalStateException.class);
    }

    /**
     * The #484 review round's root fix: a REJECTED claim cannot
     * self-reverse. The member-controlled request used to move REJECTED →
     * PENDING — and because every state except REJECTED passes the
     * community write gate, the rejected member regained publish/comment/
     * react rights without any administrator review. The entity now
     * ignores the request from REJECTED entirely (the service layer above
     * answers the explicit 409 so the silence never reaches a client).
     */
    @Test
    void rejectedVerification_cannotSelfReverse_theClaimStaysRejected() {
        NeighborhoodMembership rejected = NeighborhoodMembership.join(UUID.randomUUID(), UUID.randomUUID(), clock);
        rejected.requestVerification();
        rejected.rejectVerification();

        rejected.requestVerification();

        assertThat(rejected.getVerificationState()).isEqualTo(MembershipVerificationState.REJECTED);
        assertThat(rejected.mayUseCommunityWrites()).isFalse();
    }

    /**
     * The #484 review round's admin lever: the verdict's only mover is
     * the administrator, in BOTH directions — APPROVE re-admits a
     * REJECTED claim (the recovery lever that makes the carried verdict
     * honest), REJECT refuses a PENDING one. UNVERIFIED still answers
     * the transition refusal (no verdict exists to move).
     */
    @Test
    void theAdministratorMovesTheVerdictInBothDirections() {
        NeighborhoodMembership reAdmitted = NeighborhoodMembership.join(UUID.randomUUID(), UUID.randomUUID(), clock);
        reAdmitted.requestVerification();
        reAdmitted.rejectVerification();

        reAdmitted.approveVerification();

        assertThat(reAdmitted.getVerificationState()).isEqualTo(MembershipVerificationState.VERIFIED);
        assertThat(reAdmitted.mayUseCommunityWrites()).isTrue();

        NeighborhoodMembership unverified = NeighborhoodMembership.join(UUID.randomUUID(), UUID.randomUUID(), clock);
        assertThatThrownBy(unverified::approveVerification).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(unverified::rejectVerification).isInstanceOf(IllegalStateException.class);
    }

    /**
     * The #484 review round's verdict carry: a rejoin/switch by a user
     * whose latest row was REJECTED is born REJECTED — the refusal
     * follows the USER, not the row (leave-and-rejoin used to mint a
     * fresh UNVERIFIED row that resurrected the write gate).
     */
    @Test
    void theRejectedVerdict_isInheritedAtTheRejoinBirth() {
        NeighborhoodMembership rejoined = NeighborhoodMembership.join(UUID.randomUUID(), UUID.randomUUID(), clock);

        rejoined.inheritRejectedVerdict();

        assertThat(rejoined.getVerificationState()).isEqualTo(MembershipVerificationState.REJECTED);
        assertThat(rejoined.mayUseCommunityWrites()).isFalse();
        // the birth-inherited verdict is as unmoving by the member as a reviewed
        // one: the re-application is silently ignored here (the service above
        // answers the explicit 409), the administrator's APPROVE is the only lever
        rejoined.requestVerification();
        assertThat(rejoined.getVerificationState()).isEqualTo(MembershipVerificationState.REJECTED);
    }
}
