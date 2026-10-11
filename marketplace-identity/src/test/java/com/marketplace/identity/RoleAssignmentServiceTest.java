package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserRoleAssignmentRevoked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * Phase 1 (the unified plan §10, D-03) — the multi-role assignment
 * domain's transitions: the grant path's gate order (existence →
 * duplicate-active → insert), the revoke transition (the stamp, the
 * refresh-resurrection kill, the published fact), the state machine's
 * honest refusals, and the read. The security gates themselves are the
 * {@code RoleAssignmentServiceSecurityTest}'s subject.
 */
class RoleAssignmentServiceTest {

    private final RoleAssignmentRepository repository = mock(RoleAssignmentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    private RoleAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new RoleAssignmentService(repository, userRepository, eventPublisher,
                jdbcTemplate, clock);
    }

    private static com.marketplace.identity.User user(String subject) {
        return com.marketplace.identity.User.create(subject, subject, "Member", UserRole.CONSUMER);
    }

    // ------------------------------------------------------------------
    // grant — the write path's gate order
    // ------------------------------------------------------------------

    @Test
    void grant_storesTheActiveAssignmentWithTheAdminSource() {
        UUID userId = UUID.randomUUID();
        UUID grantorId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user("member@example.com")));
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.PROVIDER))
                .thenReturn(Optional.empty());
        when(repository.save(any(RoleAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        RoleAssignmentView view = service.grant(userId, UserRole.PROVIDER, grantorId, "admin");

        assertEquals(UserRole.PROVIDER.name(), view.role());
        assertEquals(userId, view.userId());
        assertEquals(grantorId, view.grantedBy());
        assertEquals(RoleAssignmentService.SOURCE_ADMIN, view.source());
        assertEquals(NOW, view.grantedAt());
        assertThat(view.revokedAt()).as("a fresh grant is ACTIVE — no revoke stamp").isNull();

        ArgumentCaptor<RoleAssignment> saved = ArgumentCaptor.forClass(RoleAssignment.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isActive()).isTrue();
    }

    @Test
    void grant_unknownUserAnswers404_beforeAnyWrite() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.grant(userId, UserRole.PROVIDER, UUID.randomUUID(), "admin"));
        verifyNoInteractions(repository);
    }

    @Test
    void grant_duplicateActiveRoleAnswers409() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user("member@example.com")));
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.PROVIDER))
                .thenReturn(Optional.of(RoleAssignment.grant(
                        UUID.randomUUID(), userId, UserRole.PROVIDER, NOW, null, "BACKFILL")));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.grant(userId, UserRole.PROVIDER, UUID.randomUUID(), "admin"));
        assertThat(ex.getMessage()).contains("PROVIDER").contains("active");
        verify(repository, never()).save(any(RoleAssignment.class));
    }

    // ------------------------------------------------------------------
    // revoke — the transition, its kills, its published fact
    // ------------------------------------------------------------------

    @Test
    void revoke_stampsTheRow_killsRefreshResurrection_publishesTheFact() {
        UUID userId = UUID.randomUUID();
        String subject = "member@example.com";
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(subject)));
        RoleAssignment active = RoleAssignment.grant(
                UUID.randomUUID(), userId, UserRole.PROVIDER, NOW.minusSeconds(3600), null, "BACKFILL");
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.PROVIDER))
                .thenReturn(Optional.of(active));

        RoleAssignmentView view = service.revoke(userId, UserRole.PROVIDER, "admin");

        assertEquals(NOW, view.revokedAt());
        assertThat(active.isActive()).isFalse();
        // The L23 refresh-resurrection kill — the authorization rows die
        // in the same transaction.
        verify(jdbcTemplate).update(RoleAssignmentService.DELETE_AUTHORIZATIONS_BY_PRINCIPAL, subject);
        // The session-carrier fact — published in-transaction with the
        // full payload (the invalidator consumes it at AFTER_COMMIT).
        ArgumentCaptor<UserRoleAssignmentRevoked> event =
                ArgumentCaptor.forClass(UserRoleAssignmentRevoked.class);
        verify(eventPublisher).publishEvent(event.capture());
        UserRoleAssignmentRevoked published = event.getValue();
        assertEquals(userId, published.userId());
        assertEquals(subject, published.username());
        assertEquals("PROVIDER", published.role());
    }

    @Test
    void revoke_unknownUserAnswers404() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.revoke(userId, UserRole.ADMIN, "admin"));
        verifyNoInteractions(jdbcTemplate, eventPublisher);
    }

    @Test
    void revoke_withoutAnActiveAssignmentAnswers409() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user("member@example.com")));
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.PROVIDER))
                .thenReturn(Optional.empty());

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.revoke(userId, UserRole.PROVIDER, "admin"));
        assertThat(ex.getMessage()).contains("No active PROVIDER role assignment");
        verifyNoInteractions(jdbcTemplate, eventPublisher);
    }

    @Test
    void revoke_ofAnAlreadyRevokedAssignmentAnswers409_notSilent() {
        // The derived query only ever answers ACTIVE rows — an
        // already-revoked pair is indistinguishable from an absent one
        // at the read, which is the honest refusal shape (the same 409,
        // never a silent no-op on a security-bearing transition).
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user("member@example.com")));
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.PROVIDER))
                .thenReturn(Optional.empty());

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.revoke(userId, UserRole.PROVIDER, "admin"));
        assertThat(ex.getMessage()).contains("No active PROVIDER role assignment");
        verifyNoInteractions(jdbcTemplate, eventPublisher);
    }

    // ------------------------------------------------------------------
    // The read — the account's active set
    // ------------------------------------------------------------------

    @Test
    void activeRoles_answersTheAccountActiveSetNewestFirst() {
        UUID userId = UUID.randomUUID();
        RoleAssignment older = RoleAssignment.grant(
                UUID.randomUUID(), userId, UserRole.CONSUMER, NOW.minusSeconds(7200), null, "BACKFILL");
        RoleAssignment newer = RoleAssignment.grant(
                UUID.randomUUID(), userId, UserRole.PROVIDER, NOW.minusSeconds(60), null, "ADMIN");
        when(repository.findByUserIdAndRevokedAtIsNullOrderByGrantedAtDescIdDesc(userId))
                .thenReturn(List.of(newer, older));

        List<RoleAssignmentView> views = service.activeRoles(userId);

        assertEquals(2, views.size());
        assertEquals("PROVIDER", views.get(0).role());
        assertEquals("CONSUMER", views.get(1).role());
    }

    // ------------------------------------------------------------------
    // The re-grant after a revoke — the freed pair accepts a fresh row
    // ------------------------------------------------------------------

    @Test
    void regrant_afterRevoke_insertsAFreshRow() {
        // After the revoke the derived duplicate read answers empty
        // (revoked_at IS NULL fails) — the pair is free, the fresh row
        // carries its own grant stamp (the history stays server-side).
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user("member@example.com")));
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.PROVIDER))
                .thenReturn(Optional.empty());
        when(repository.save(any(RoleAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        RoleAssignmentView view = service.grant(userId, UserRole.PROVIDER, UUID.randomUUID(), "admin");

        assertThat(view.revokedAt()).isNull();
        verify(repository).save(any(RoleAssignment.class));
        verify(eventPublisher, never()).publishEvent(any());
    }
}
