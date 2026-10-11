package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.TrustType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 1 (the unified plan §10) — the verification attestation's
 * lifecycle: the self-service request with its required evidence, the
 * review machine (PENDING → GRANTED / REJECTED), the withdrawal
 * (GRANTED → REVOKED), the honest refusals, and the §6.5 separation —
 * the four types are independent facts with independent (subject,
 * type) uniqueness. The security gates themselves are the
 * {@code VerificationAttestationServiceSecurityTest}'s subject.
 */
class VerificationAttestationServiceTest {

    private final VerificationAttestationRepository repository =
            mock(VerificationAttestationRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    private VerificationAttestationService service;

    @BeforeEach
    void setUp() {
        service = new VerificationAttestationService(repository, userRepository, clock);
    }

    private static com.marketplace.identity.User user(String subject) {
        return com.marketplace.identity.User.create(subject, subject, "Member", UserRole.CONSUMER);
    }

    // ------------------------------------------------------------------
    // request — the self-service birth transition
    // ------------------------------------------------------------------

    @Test
    void request_storesAPendingRowWithItsEvidence() {
        UUID subjectId = UUID.randomUUID();
        when(userRepository.findById(subjectId)).thenReturn(Optional.of(user("member@example.com")));
        when(repository.findBySubjectUserIdAndTrustTypeAndState(
                subjectId, TrustType.VERIFIED_LOCAL_MEMBER, VerificationAttestationState.GRANTED))
                .thenReturn(Optional.empty());
        when(repository.save(any(VerificationAttestation.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VerificationAttestationView view = service.request(
                subjectId, TrustType.VERIFIED_LOCAL_MEMBER, "geo:membership/abc", "member");

        assertEquals("PENDING", view.state());
        assertEquals("VERIFIED_LOCAL_MEMBER", view.trustType());
        assertEquals("geo:membership/abc", view.evidenceRef());
        assertThat(view.grantedAt()).isNull();
    }

    @Test
    void request_unknownSubjectAnswers404() {
        UUID subjectId = UUID.randomUUID();
        when(userRepository.findById(subjectId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.request(subjectId, TrustType.VERIFIED_LOCAL_MEMBER, "ev", "member"));
        verifyNoInteractions(repository);
    }

    @Test
    void request_whenThePairIsAlreadyGrantedAnswers409() {
        UUID subjectId = UUID.randomUUID();
        when(userRepository.findById(subjectId)).thenReturn(Optional.of(user("member@example.com")));
        when(repository.findBySubjectUserIdAndTrustTypeAndState(
                subjectId, TrustType.VERIFIED_BUSINESS, VerificationAttestationState.GRANTED))
                .thenReturn(Optional.of(VerificationAttestation.request(
                        UUID.randomUUID(), subjectId, TrustType.VERIFIED_BUSINESS, "ev")));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.request(subjectId, TrustType.VERIFIED_BUSINESS, "ev2", "member"));
        assertThat(ex.getMessage()).contains("VERIFIED_BUSINESS").contains("GRANTED");
        verify(repository, never()).save(any(VerificationAttestation.class));
    }

    // ------------------------------------------------------------------
    // review — the reviewer's decision
    // ------------------------------------------------------------------

    @Test
    void grant_movesPendingToGrantedWithTheReviewerRecorded() {
        UUID reviewerId = UUID.randomUUID();
        VerificationAttestation pending = VerificationAttestation.request(
                UUID.randomUUID(), UUID.randomUUID(), TrustType.VERIFIED_LOCAL_MEMBER, "ev");
        when(repository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(repository.findBySubjectUserIdAndTrustTypeAndState(
                pending.getSubjectUserId(), TrustType.VERIFIED_LOCAL_MEMBER,
                VerificationAttestationState.GRANTED))
                .thenReturn(Optional.empty());

        VerificationAttestationView view = service.grant(pending.getId(), reviewerId, "admin");

        assertEquals("GRANTED", view.state());
        assertEquals(reviewerId, view.grantedBy());
        assertEquals(NOW, view.grantedAt());
    }

    @Test
    void grant_whenAnotherAttestationOfThePairIsAlreadyGrantedAnswers409() {
        VerificationAttestation pending = VerificationAttestation.request(
                UUID.randomUUID(), UUID.randomUUID(), TrustType.VERIFIED_BUSINESS, "ev");
        when(repository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(repository.findBySubjectUserIdAndTrustTypeAndState(
                pending.getSubjectUserId(), TrustType.VERIFIED_BUSINESS,
                VerificationAttestationState.GRANTED))
                .thenReturn(Optional.of(VerificationAttestation.request(
                        UUID.randomUUID(), pending.getSubjectUserId(),
                        TrustType.VERIFIED_BUSINESS, "older")));

        assertThrows(ConflictException.class,
                () -> service.grant(pending.getId(), UUID.randomUUID(), "admin"));
        assertThat(pending.getState()).isEqualTo(VerificationAttestationState.PENDING);
    }

    @Test
    void grant_unknownAttestationAnswers404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.grant(id, UUID.randomUUID(), "admin"));
    }

    @Test
    void reject_movesPendingToRejected() {
        VerificationAttestation pending = VerificationAttestation.request(
                UUID.randomUUID(), UUID.randomUUID(), TrustType.COMMUNITY_ENDORSEMENT, "ev");
        when(repository.findById(pending.getId())).thenReturn(Optional.of(pending));

        VerificationAttestationView view = service.reject(pending.getId(), "admin");

        assertEquals("REJECTED", view.state());
        assertThat(view.grantedAt()).as("a rejection grants nothing").isNull();
    }

    // ------------------------------------------------------------------
    // revoke — the withdrawal of a granted fact
    // ------------------------------------------------------------------

    @Test
    void revoke_movesGrantedToRevokedWithTheStamp() {
        VerificationAttestation granted = VerificationAttestation.request(
                UUID.randomUUID(), UUID.randomUUID(), TrustType.VERIFIED_SOURCE, "ev");
        granted.grant(UUID.randomUUID(), NOW.minusSeconds(3600));
        when(repository.findById(granted.getId())).thenReturn(Optional.of(granted));

        VerificationAttestationView view = service.revoke(granted.getId(), "admin");

        assertEquals("REVOKED", view.state());
        assertEquals(NOW, view.revokedAt());
    }

    @Test
    void revoke_ofAPendingAttestationRefusesTheTransition() {
        VerificationAttestation pending = VerificationAttestation.request(
                UUID.randomUUID(), UUID.randomUUID(), TrustType.VERIFIED_SOURCE, "ev");
        when(repository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThrows(IllegalStateException.class,
                () -> service.revoke(pending.getId(), "admin"));
        assertThat(pending.getState()).isEqualTo(VerificationAttestationState.PENDING);
    }

    // ------------------------------------------------------------------
    // The read — the subject's own history
    // ------------------------------------------------------------------

    @Test
    void listOwn_answersTheSubjectHistoryNewestFirst() {
        UUID subjectId = UUID.randomUUID();
        VerificationAttestation pending = VerificationAttestation.request(
                UUID.randomUUID(), subjectId, TrustType.VERIFIED_LOCAL_MEMBER, "ev");
        VerificationAttestation revoked = VerificationAttestation.request(
                UUID.randomUUID(), subjectId, TrustType.VERIFIED_BUSINESS, "ev");
        revoked.grant(UUID.randomUUID(), NOW.minusSeconds(7200));
        revoked.revoke(NOW.minusSeconds(3600));
        when(repository.findBySubjectUserIdOrderByCreatedAtDescIdDesc(subjectId))
                .thenReturn(List.of(revoked, pending));

        List<VerificationAttestationView> views = service.listOwn(subjectId);

        assertEquals(2, views.size());
        assertEquals("REVOKED", views.get(0).state());
        assertEquals("PENDING", views.get(1).state());
    }
}
