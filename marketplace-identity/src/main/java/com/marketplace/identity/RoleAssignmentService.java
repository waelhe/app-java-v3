package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserRoleAssignmentRevoked;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 1 (the unified plan §10, D-03 — §3.1's P0 «UserRole أحادي» gap):
 * the multi-role grant/revoke/read domain. One account may hold several
 * of the closed V1 roles as independent ACTIVE assignments; the V183
 * effective-authorities view unions them into the login-side authority
 * read, so an active assignment IS an authority at the next token mint.
 *
 * <p><b>The ADMIN gate at the service boundary (the A-07 three-layer
 * pattern — chain rule + controller class rule + THIS):</b> both
 * mutating commands carry {@code @PreAuthorize("hasRole('ADMIN')")} —
 * the same third layer {@code UserService.updateUserRole} carries,
 * measured by its own {@code *ServiceSecurityTest} (unannotated methods
 * are not secured — the Spring Security Reference's own words — so the
 * bean called off the HTTP path must defend itself). There is no admin
 * HTTP surface in this wave: the commands are the module's own contract
 * for the administrative surfaces that follow, and the general read
 * outlet is the caller's own {@code /me/roles}.
 *
 * <p><b>The write path's gates, ordered (the {@code FollowService}
 * shape):</b> the target's existence first (the clean 404 — the row is
 * born pointing at a live account), then the duplicate-active read (the
 * clean 409 with the state machine's own words), then the insert —
 * V182's {@code uq_user_role_assignments_one_active} partial unique
 * index is the concurrent-insert backstop (the two-layer model
 * verbatim). A revoke after a revoke is refused (409 — the transition
 * already happened); a re-grant after a revoke inserts a FRESH row (the
 * history stays).
 *
 * <p><b>The REVOKE's two fail-open carriers die with the command (the
 * L23 documented basis, the identical store {@code UserService}
 * cleans):</b> the account's {@code oauth2_authorization} rows are
 * removed in the same transaction — a pre-revoke refresh token
 * deserializes the principal stored in the authorization (it would
 * otherwise keep minting the withdrawn authority), so the rows die and
 * the next refresh is an honest re-login. The other carrier — the LIVE
 * SESSION whose cached authorities still hold the role — is closed by
 * {@link UserRoleAssignmentRevoked} (published in-transaction, consumed
 * by {@code AccountStatusSessionInvalidator} at AFTER_COMMIT — the R8
 * consumer, the documented asymmetry: a GRANT is fail-closed for live
 * sessions, so it publishes nothing and has no consumer to invent).
 * Access tokens in flight die by their documented 900s TTL; the V183
 * view drops the revoked row at the very next login either way.
 *
 * <p><b>users.role is deliberately untouched</b> (the Phase-1 letter):
 * the primary role field and its S2/N4/N6 two-store choreography keep
 * working; the assignment model is the additive multi-role layer the
 * security boundary unions — the {@code UserRoleChanged} primary-role
 * event and this module's {@code UserRoleAssignmentRevoked} describe two
 * different facts about two different stores.
 *
 * <p><b>The audit record</b> (the payments/UserService convention): one
 * structured line per command — actor, target, role, action, source.
 */
@Service
@Transactional
public class RoleAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(RoleAssignmentService.class);

    /**
     * The V182 closed source vocabulary, service-side half: the
     * administrative grant surface (the BACKFILL half belongs to the
     * migration alone — a live service call can never forge it).
     */
    static final String SOURCE_ADMIN = "ADMIN";

    /**
     * The L23 refresh-resurrection kill — the exact statement
     * {@code UserService.updateUserRole} runs on the role change (the
     * documented SAS basis: the refresh grant deserializes the stored
     * principal instead of re-reading the account).
     */
    static final String DELETE_AUTHORIZATIONS_BY_PRINCIPAL =
            "DELETE FROM oauth2_authorization WHERE principal_name = ?";

    private final RoleAssignmentRepository repository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public RoleAssignmentService(RoleAssignmentRepository repository,
                                 UserRepository userRepository,
                                 ApplicationEventPublisher eventPublisher,
                                 JdbcTemplate jdbcTemplate,
                                 Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Grant one role to one account — an ACTIVE assignment row, born
     * pointing at a live account (404 otherwise) and free of an active
     * duplicate of the same role (409 otherwise). The caller is the
     * administrative surface; {@code grantedBy} is recorded on the row
     * (NULL = a system act) and {@code actor} rides the audit line.
     */
    @Observed(name = "identity.role.grant")
    @PreAuthorize("hasRole('ADMIN')")
    public RoleAssignmentView grant(UUID userId, UserRole role, UUID grantedBy, String actor) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, role)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "The account already holds an active " + role.name() + " role assignment");
                });
        RoleAssignment saved = repository.save(
                RoleAssignment.grant(UUID.randomUUID(), userId, role, clock.instant(), grantedBy, SOURCE_ADMIN));
        log.info("Role assignment audit: userId={}, username={}, role={}, action=GRANT, source={}, actor={}",
                userId, user.getSubject(), role, SOURCE_ADMIN, actor);
        return RoleAssignmentView.of(saved);
    }

    /**
     * Revoke the account's ACTIVE assignment of one role — the one
     * transition the active state accepts, refusing an already-revoked
     * (or absent) pair with the honest 404/409 (see the class javadoc
     * for the two fail-open carriers that die with this command).
     */
    @Observed(name = "identity.role.revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public RoleAssignmentView revoke(UUID userId, UserRole role, String actor) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        RoleAssignment active = repository.findByUserIdAndRoleAndRevokedAtIsNull(userId, role)
                .orElseThrow(() -> new ConflictException(
                        "No active " + role.name() + " role assignment to revoke for this account"));
        active.revoke(clock.instant());

        // The refresh-resurrection kill (the L23 documented basis) — the
        // authorization rows die in the same transaction as the revoke.
        jdbcTemplate.update(DELETE_AUTHORIZATIONS_BY_PRINCIPAL, user.getSubject());

        // The session-carrier kill — published in-transaction (the
        // UserRoleChanged house shape), consumed at AFTER_COMMIT.
        eventPublisher.publishEvent(
                new UserRoleAssignmentRevoked(userId, user.getSubject(), role.name()));

        log.info("Role assignment audit: userId={}, username={}, role={}, action=REVOKE, actor={}",
                userId, user.getSubject(), role, actor);
        return RoleAssignmentView.of(active);
    }

    /**
     * The account's ACTIVE assignments, newest first (the L32
     * deterministic order). The read behind the general read outlet
     * ({@code /me/roles}) — no ADMIN gate: an account's own active
     * roles are the account's own fact (the same honesty that lets
     * {@code /users/me} read its own row).
     */
    @Transactional(readOnly = true)
    public List<RoleAssignmentView> activeRoles(UUID userId) {
        return repository.findByUserIdAndRevokedAtIsNullOrderByGrantedAtDescIdDesc(userId)
                .stream()
                .map(RoleAssignmentView::of)
                .toList();
    }
}
