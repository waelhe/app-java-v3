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

    @Test
    void revoke_ofTheAccountsPrimaryRoleAnswers409_routedToTheTwoStoreRoleUpdate() {
        // The primary role's assignment is the S2/N4/N6 choreography's
        // own row — revoking it would leave users.role and its
        // auth_authorities projection intact, so the authority would
        // survive the revoke through the V188 union's login-side leg
        // while /me/roles and the effective authorities disagree. The
        // refusal precedes every write (the CodeRabbit #543 round); the
        // honest path is UserService.updateUserRole.
        UUID userId = UUID.randomUUID();
        RoleAssignment primary = RoleAssignment.grant(
                UUID.randomUUID(), userId, UserRole.CONSUMER, NOW.minusSeconds(3600), null, "BACKFILL");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user("member@example.com")));
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.CONSUMER))
                .thenReturn(Optional.of(primary));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.revoke(userId, UserRole.CONSUMER, "admin"));
        assertThat(ex.getMessage()).contains("primary").contains("CONSUMER");
        assertThat(primary.isActive())
                .as("the refusal precedes every write — the row stays active")
                .isTrue();
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

    // ------------------------------------------------------------------
    // onPrimaryRoleChanged — the primary-change choreography's assignment
    // leg (CodeRabbit agent findings, row 26 / CWE-269)
    // ------------------------------------------------------------------

    @Test
    void primaryChange_retiresTheFormerMirrorAndEnsuresTheNewOne() {
        // The demotion scenario the finding measured: the ADMIN mirror row
        // survives the primary change, the V188 union resurrects ROLE_ADMIN
        // at the next login. The choreography leg retires the former mirror
        // and grants the new primary's mirror when absent.
        UUID userId = UUID.randomUUID();
        RoleAssignment adminMirror = RoleAssignment.grant(
                UUID.randomUUID(), userId, UserRole.ADMIN, NOW.minusSeconds(3600), null, "BACKFILL");
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.ADMIN))
                .thenReturn(Optional.of(adminMirror));
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.CONSUMER))
                .thenReturn(Optional.empty());
        when(repository.save(any(RoleAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onPrimaryRoleChanged(userId, UserRole.ADMIN, UserRole.CONSUMER, "admin");

        assertThat(adminMirror.isActive())
                .as("the former primary's mirrored assignment is retired with the change")
                .isFalse();
        verify(repository).save(adminMirror);
        ArgumentCaptor<RoleAssignment> granted = ArgumentCaptor.forClass(RoleAssignment.class);
        verify(repository, org.mockito.Mockito.times(2)).save(granted.capture());
        assertThat(granted.getAllValues().get(1).getId()).isNotNull();
    }

    @Test
    void primaryChange_anExistingAdditiveGrantBecomesTheMirror_noDuplicateInsert() {
        // An account holding an independent additive grant of the TARGET
        // role keeps it as the mirror itself — the duplicate-active gate
        // (V187's partial unique index) must never see a second insert.
        UUID userId = UUID.randomUUID();
        RoleAssignment formerMirror = RoleAssignment.grant(
                UUID.randomUUID(), userId, UserRole.CONSUMER, NOW.minusSeconds(3600), null, "BACKFILL");
        RoleAssignment additiveAdmin = RoleAssignment.grant(
                UUID.randomUUID(), userId, UserRole.ADMIN, NOW.minusSeconds(1800), null, "ADMIN");
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.CONSUMER))
                .thenReturn(Optional.of(formerMirror));
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.ADMIN))
                .thenReturn(Optional.of(additiveAdmin));

        service.onPrimaryRoleChanged(userId, UserRole.CONSUMER, UserRole.ADMIN, "admin");

        assertThat(formerMirror.isActive()).isFalse();
        assertThat(additiveAdmin.isActive())
                .as("the independent additive grant stays active and becomes the mirror")
                .isTrue();
        verify(repository).save(formerMirror);
        verify(repository, org.mockito.Mockito.times(1)).save(any(RoleAssignment.class));
    }

    @Test
    void primaryChange_anAbsentFormerMirrorIsACleanNoOpRetire() {
        // A drifted account (no mirror row for the former primary — the
        // pre-V187 stock) takes the retire as a no-op; the new primary's
        // mirror is still ensured.
        UUID userId = UUID.randomUUID();
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.CONSUMER))
                .thenReturn(Optional.empty());
        when(repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, UserRole.PROVIDER))
                .thenReturn(Optional.empty());
        when(repository.save(any(RoleAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        service.onPrimaryRoleChanged(userId, UserRole.CONSUMER, UserRole.PROVIDER, "admin");

        ArgumentCaptor<RoleAssignment> granted = ArgumentCaptor.forClass(RoleAssignment.class);
        verify(repository, org.mockito.Mockito.times(1)).save(granted.capture());
        assertThat(granted.getValue().isActive()).isTrue();
    }
}
