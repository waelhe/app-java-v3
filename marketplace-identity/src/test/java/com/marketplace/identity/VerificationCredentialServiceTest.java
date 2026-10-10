package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.VerificationCredentialDecided;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR-0001 (plan §Phase 1) — the verification credential lifecycle slice:
 * the state machine measured positive AND negative, the one-ACTIVE-per-type
 * submit gate, and the authorization third layer on the admin decisions.
 *
 * <p><b>The ADR-0001 separation, pinned as behavior:</b> a decision command
 * publishes exactly ONE fact ({@link VerificationCredentialDecided}) and
 * touches NO role store — {@code verifyNoInteractions(userRepository)} after
 * every decision asserts the identity user store is not even read, let alone
 * written: evidence never mints authority.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { VerificationCredentialService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class VerificationCredentialServiceTest {

    @Autowired
    private VerificationCredentialService service;

    @MockitoBean
    private VerificationCredentialRepository repository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ApplicationEventPublisher eventPublisher;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private void stubAccount() {
        when(userRepository.findBySubject("sub-1")).thenReturn(Optional.of(
                com.marketplace.identity.User.create("sub-1", "sub-1@example.com", "A", UserRole.CONSUMER)));
    }

    private static VerificationCredential pendingIdentity() {
        return VerificationCredential.submit(USER_ID, VerificationCredentialType.IDENTITY,
                "https://evidence/id-1", "my identity document");
    }

    // -- The submit gate --

    @Test
    void submit_createsAPendingCredential() {
        stubAccount();
        when(repository.existsByUserIdAndCredentialTypeAndStatusIn(
                eq(USER_ID), eq(VerificationCredentialType.IDENTITY), anyCollection()))
                .thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VerificationCredential credential =
                service.submit("sub-1", "IDENTITY", "https://evidence/id-1", "my identity document");

        assertThat(credential.getStatus()).isEqualTo(VerificationCredentialStatus.PENDING);
        assertThat(credential.getCredentialType()).isEqualTo(VerificationCredentialType.IDENTITY);
        assertThat(credential.getUserId()).isEqualTo(USER_ID);
    }

    @Test
    void submit_withAnActiveApplicationOfTheSameType_isConflict() {
        stubAccount();
        when(repository.existsByUserIdAndCredentialTypeAndStatusIn(
                eq(USER_ID), eq(VerificationCredentialType.IDENTITY), anyCollection()))
                .thenReturn(true);

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> service.submit("sub-1", "IDENTITY", "https://evidence/id-2", "again"));
        verify(repository, never()).save(any());
    }

    @Test
    void submit_ofUnknownType_isBadRequest() {
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> service.submit("sub-1", "KNIGHTHOOD", null, null));
    }

    // -- The decision paths: the state machine --

    @Test
    @WithMockUser(roles = "ADMIN")
    void decide_approvesFromPending_andPublishesExactlyTheDecisionFact() {
        VerificationCredential credential = pendingIdentity();
        when(repository.findById(credential.getId())).thenReturn(Optional.of(credential));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VerificationCredential decided =
                service.decide(credential.getId(), "APPROVED", "verified", "admin-1");

        assertThat(decided.getStatus()).isEqualTo(VerificationCredentialStatus.APPROVED);
        assertThat(decided.getReviewedBy()).isEqualTo("admin-1");
        assertThat(decided.getDecisionNotes()).isEqualTo("verified");

        // EXACTLY one fact — the credential decision — and nothing else:
        // no role change, no cache invalidation, no authority of any kind.
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        Assertions.assertThat(events.getAllValues())
                .singleElement()
                .isInstanceOf(VerificationCredentialDecided.class);
        VerificationCredentialDecided fact = (VerificationCredentialDecided) events.getValue();
        assertThat(fact.credentialType()).isEqualTo("IDENTITY");
        assertThat(fact.decision()).isEqualTo("APPROVED");
        assertThat(fact.actor()).isEqualTo("admin-1");
        // The ADR-0001 boundary: the user store is never even read by a decision.
        verifyNoInteractions(userRepository);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void decide_rejectsFromPending() {
        VerificationCredential credential = pendingIdentity();
        when(repository.findById(credential.getId())).thenReturn(Optional.of(credential));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VerificationCredential decided = service.decide(credential.getId(), "REJECTED", "unclear", "admin-1");

        assertThat(decided.getStatus()).isEqualTo(VerificationCredentialStatus.REJECTED);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void decide_ofAnAlreadyDecidedCredential_isConflict() {
        VerificationCredential credential = pendingIdentity();
        credential.approve("admin-0", null);
        when(repository.findById(credential.getId())).thenReturn(Optional.of(credential));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> service.decide(credential.getId(), "REJECTED", null, "admin-1"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void decide_ofUnknownDecisionName_isBadRequest() {
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> service.decide(UUID.randomUUID(), "MAYBE", null, "admin-1"));
    }

    // -- The revocation path --

    @Test
    @WithMockUser(roles = "ADMIN")
    void revoke_fromApproved_toRevoked() {
        VerificationCredential credential = pendingIdentity();
        credential.approve("admin-0", null);
        when(repository.findById(credential.getId())).thenReturn(Optional.of(credential));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VerificationCredential revoked = service.revoke(credential.getId(), "lapsed", "admin-1");

        assertThat(revoked.getStatus()).isEqualTo(VerificationCredentialStatus.REVOKED);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revoke_fromPending_isConflict() {
        VerificationCredential credential = pendingIdentity();
        when(repository.findById(credential.getId())).thenReturn(Optional.of(credential));

        assertThatExceptionOfType(ConflictException.class)
                .isThrownBy(() -> service.revoke(credential.getId(), null, "admin-1"));
    }

    // -- The authorization third layer (negative first — the house convention) --

    @Test
    @WithMockUser(roles = "CONSUMER")
    void decide_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.decide(UUID.randomUUID(), "APPROVED", null, "actor"));
        verifyNoInteractions(repository);
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void revoke_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.revoke(UUID.randomUUID(), null, "actor"));
        verifyNoInteractions(repository);
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void adminQueue_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.adminPage(null, org.springframework.data.domain.PageRequest.of(0, 20)));
        verifyNoInteractions(repository);
    }
}
