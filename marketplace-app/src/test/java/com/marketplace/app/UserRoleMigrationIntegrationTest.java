package com.marketplace.app;

import com.marketplace.identity.RoleAssignmentService;
import com.marketplace.identity.UserService;
import com.marketplace.identity.VerificationAttestationService;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.TrustType;
import test.config.IntegrationContainers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 (the unified plan §10 — D-03 multi-role + the §6.5 trust
 * vocabulary) — the wave's migration on the REAL booted chain (real
 * Flyway, {@code ddl-auto=none}, the {@code ListingPriceCalendarIntegrationTest}
 * posture): the login-side authorities must gather the ACTIVE multi-role
 * assignments through the ONE official seam —
 * {@code JdbcUserDetailsManager#loadUserByUsername} reading the V188
 * effective-authorities view — with no parallel mechanism, and the closed
 * vocabularies must refuse the forbidden states on PostgreSQL itself.
 *
 * <p>The acceptance rows this class closes (the Phase-1 gate):
 * <ul>
 *   <li>an active assignment IS an authority at the next login, a revoked
 *       one is not — measured through the framework's own
 *       {@code loadUserByUsername}, not through a side query;</li>
 *   <li>the union is the old behavior PLUS exactly the granted
 *       assignments: the mirrored (backfill-shaped) assignment collapses
 *       into the login-side row it duplicates (the pre-migration authority
 *       set, byte for byte), the soft-deleted membership carries nothing,
 *       and the auth-only break-glass principal is untouched;</li>
 *   <li>the closed vocabularies (roles, sources, trust types, attestation
 *       states) refuse invented values, the partial unique indexes refuse
 *       the second active/granted row, and {@code users.role} survives
 *       byte-untouched (NOT NULL, its V1 CHECK intact);</li>
 *   <li>the attestation lifecycle rides the real tables end to end —
 *       PENDING → GRANTED → REVOKED with the reviewer recorded.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // The migration tests hold pristine-database contracts — the
        // trial-zone content stays out (the application-test.yml override
        // already binds this; restated on the class for its own reader).
        "spring.flyway.placeholders.seedContent=false"
})
@Testcontainers(disabledWithoutDocker = true)
class UserRoleMigrationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private UserService userService;

    @Autowired
    private VerificationAttestationService attestationService;

    @AfterEach
    void cleanTheSeededRows() {
        // FK-safe order; the prefix scopes the cleanup to THIS class's
        // own seeds (the pristine-database house posture).
        jdbc.update("delete from user_role_assignments where user_id in "
                + "(select id from users where subject like 'phase1-it-%')");
        jdbc.update("delete from user_role_assignments_aud where user_id in "
                + "(select id from users where subject like 'phase1-it-%')");
        jdbc.update("delete from verification_attestations where subject_user_id in "
                + "(select id from users where subject like 'phase1-it-%')");
        jdbc.update("delete from verification_attestations_aud where subject_user_id in "
                + "(select id from users where subject like 'phase1-it-%')");
        jdbc.update("delete from auth_authorities where username like 'phase1-it-%'");
        jdbc.update("delete from auth_users where username like 'phase1-it-%'");
        jdbc.update("delete from users where subject like 'phase1-it-%'");
    }

    // -- seeds -------------------------------------------------------------

    private UUID seedAccount(String suffix, String subject, String role, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, subject, email, display_name, role, is_deleted) "
                        + "values (?, ?, ?, ?, ?, ?)",
                id, subject, subject, "Account " + suffix, role, deleted);
        return id;
    }

    private void seedAuthUser(String subject, String... authorities) {
        jdbc.update("insert into auth_users (username, password, enabled) values (?, 'it-noop', true)",
                subject);
        for (String authority : authorities) {
            jdbc.update("insert into auth_authorities (username, authority) values (?, ?)",
                    subject, authority);
        }
    }

    private void seedAssignment(UUID userId, String role, boolean revoked) {
        jdbc.update("insert into user_role_assignments (id, user_id, role, source, revoked_at) "
                        + "values (?, ?, ?, 'ADMIN', ?)",
                UUID.randomUUID(), userId, role, revoked ? java.time.Instant.now() : null);
    }

    private Set<String> authoritiesOf(String username) {
        UserDetails details = userDetailsManager.loadUserByUsername(username);
        return details.getAuthorities().stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    // -- the official authorities chain ------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void activeAssignmentReachesTheAuthoritiesThroughTheOfficialChain() {
        String subject = "phase1-it-chain@example.com";
        UUID userId = seedAccount("chain", subject, "CONSUMER", false);
        seedAuthUser(subject, "ROLE_CONSUMER");

        // BEFORE any assignment: exactly the login-side row.
        assertThat(authoritiesOf(subject)).containsExactlyInAnyOrder("ROLE_CONSUMER");

        // The REAL service grant (the ADMIN gate opens for the mocked
        // admin) — the view answers the new authority at the next load.
        roleAssignmentService.grant(userId, com.marketplace.identity.UserRole.PROVIDER,
                userId, "phase1-it-admin");
        assertThat(authoritiesOf(subject))
                .as("the active assignment IS an authority at the next login")
                .containsExactlyInAnyOrder("ROLE_CONSUMER", "ROLE_PROVIDER");

        // The REAL service revoke — the view drops the withdrawn role at
        // the next login (the view is the repair path; the session/refresh
        // carriers are the documented consumer's).
        roleAssignmentService.revoke(userId, com.marketplace.identity.UserRole.PROVIDER,
                "phase1-it-admin");
        assertThat(authoritiesOf(subject))
                .as("the revoked assignment is no authority anywhere")
                .containsExactlyInAnyOrder("ROLE_CONSUMER");

        // The freed pair accepts a fresh grant (a new row, the history stays).
        roleAssignmentService.grant(userId, com.marketplace.identity.UserRole.PROVIDER,
                userId, "phase1-it-admin");
        assertThat(authoritiesOf(subject))
                .containsExactlyInAnyOrder("ROLE_CONSUMER", "ROLE_PROVIDER");

        // The duplicate-active rule answers the honest 409.
        assertThatThrownBy(() -> roleAssignmentService.grant(
                        userId, com.marketplace.identity.UserRole.PROVIDER, userId, "phase1-it-admin"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PROVIDER");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theDriftedAuthorityDiesWithItsAssignment_theRegrantRestoresIt() {
        // The CodeRabbit #543 re-review round: a drifted pair (users.role
        // CONSUMER, login-side ROLE_ADMIN — the pre-fix stock) whose
        // backfill-shaped ADMIN assignment is revoked must lose the
        // authority through the view's revoked-authority filter — the
        // first leg refuses to serve a login row whose pair carries a
        // non-active assignment; an active re-grant restores it through
        // the second leg.
        String subject = "phase1-it-driftrevoke@example.com";
        UUID userId = seedAccount("driftrevoke", subject, "CONSUMER", false);
        seedAuthUser(subject, "ROLE_CONSUMER", "ROLE_ADMIN");
        seedAssignment(userId, "ADMIN", false);

        assertThat(authoritiesOf(subject))
                .as("the drifted pair reads its login-side truth before any revoke")
                .containsExactlyInAnyOrder("ROLE_CONSUMER", "ROLE_ADMIN");

        roleAssignmentService.revoke(userId, com.marketplace.identity.UserRole.ADMIN,
                "phase1-it-admin");
        assertThat(authoritiesOf(subject))
                .as("the revoked assignment kills the drifted authority through the filter")
                .containsExactlyInAnyOrder("ROLE_CONSUMER");

        roleAssignmentService.grant(userId, com.marketplace.identity.UserRole.ADMIN,
                userId, "phase1-it-admin");
        assertThat(authoritiesOf(subject))
                .as("the active re-grant restores the authority")
                .containsExactlyInAnyOrder("ROLE_CONSUMER", "ROLE_ADMIN");
    }

    /**
     * CodeRabbit agent findings, row 19 / CWE-863 (LOW): the last-admin
     * guard must count the SAME authority source authentication reads.
     * The finding's own chain, walked end to end on the real legs — an
     * account completes a password reset while holding an additive ADMIN
     * grant (the reset's {@code updateUser} replay copies the loaded
     * effective authorities into NATIVE rows), the grant is then revoked
     * (the assignment dies; the copied native {@code ROLE_ADMIN} row
     * survives), and an administrator later requests disabling the LAST
     * EFFECTIVE administrator: a guard counting native rows sees two and
     * approves — the platform is left with zero administrators who can
     * act. The fixed guard counts the V188 view (the revoked-pair filter
     * drops the stale copy), so the removal answers the same 409 the
     * counting constraint has always promised.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void theLastAdminGuardCountsEffectiveAuthorities_notStaleNativeCopies() {
        // The last REAL administrator — the maintained V187/V188 shape:
        // users.role ADMIN, the native projection row, the mirrored
        // active assignment.
        String admin = "phase1-it-lastadmin@example.com";
        UUID adminId = seedAccount("lastadmin", admin, "ADMIN", false);
        seedAuthUser(admin, "ROLE_ADMIN");
        seedAssignment(adminId, "ADMIN", false);

        // The stale-copy carrier: a CONSUMER whose additive ADMIN grant
        // straddles a password reset (the CONSUMER mirror is the V187
        // backfill's own maintained shape).
        String stale = "phase1-it-stalecopy@example.com";
        UUID staleId = seedAccount("stalecopy", stale, "CONSUMER", false);
        seedAuthUser(stale, "ROLE_CONSUMER");
        seedAssignment(staleId, "CONSUMER", false);
        roleAssignmentService.grant(staleId, com.marketplace.identity.UserRole.ADMIN,
                adminId, "phase1-it-admin");

        // The password reset's own replay (PasswordResetService.completeReset
        // verbatim): load through the manager — the VIEW, so the active
        // grant is present — then persist through updateUser, whose
        // authority writes are NATIVE. The native store now carries the
        // ROLE_ADMIN copy.
        UserDetails withGrant = userDetailsManager.loadUserByUsername(stale);
        assertThat(withGrant.getAuthorities())
                .as("the reset loads the effective set — the grant is present")
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        userDetailsManager.updateUser(org.springframework.security.core.userdetails.User
                .withUsername(stale)
                .password(withGrant.getPassword())
                .authorities(withGrant.getAuthorities())
                .accountExpired(!withGrant.isAccountNonExpired())
                .accountLocked(!withGrant.isAccountNonLocked())
                .credentialsExpired(!withGrant.isCredentialsNonExpired())
                .disabled(!withGrant.isEnabled())
                .build());

        // The administrative revoke — the assignment dies, the copied
        // native row stays (the projection's own write path never ran).
        roleAssignmentService.revoke(staleId, com.marketplace.identity.UserRole.ADMIN,
                "phase1-it-admin");

        // The world the finding builds, measured on both stores:
        Integer staleNativeCopies = jdbc.queryForObject(
                "select count(*) from auth_authorities "
                        + "where username = ? and authority = 'ROLE_ADMIN'",
                Integer.class, stale);
        assertThat(staleNativeCopies)
                .as("the stale native copy outlives the revoked grant (the finding's premise)")
                .isEqualTo(1);
        assertThat(authoritiesOf(stale))
                .as("the view — what the next login serves — carries no ADMIN")
                .containsExactlyInAnyOrder("ROLE_CONSUMER");

        // The guard's world through its own counting query: exactly the
        // one real administrator (the pristine test database seeds no
        // other — the admin initializer is a no-op under the test
        // profile's blank seed password).
        Integer effectiveAdmins = jdbc.queryForObject(
                "select count(*) from auth_users u where u.enabled = true and exists "
                        + "(select 1 from auth_effective_authorities a "
                        + "where a.username = u.username and a.authority = 'ROLE_ADMIN')",
                Integer.class);
        assertThat(effectiveAdmins)
                .as("the effective count the fixed guard reads").isEqualTo(1);

        // The finding's sink through the real service: the disable of the
        // last EFFECTIVE administrator is refused — the stale copy must
        // not unblock it (the old native count read 2 and approved).
        assertThatThrownBy(() -> userService.updateUserStatus(
                        adminId, "DISABLED", "row 19 evidence", "phase1-it-admin"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("last active ADMIN");
    }

    @Test
    void theViewUnionsWithoutDuplicationAndWithoutTheDeadRows() {
        // (a) The mirrored assignment collapses into the login-side row it
        // duplicates — the union is the OLD behavior, byte for byte (the
        // maintained state: auth_authorities == users.role, and the
        // backfill-shaped assignment mirrors users.role).
        String mirrored = "phase1-it-mirror@example.com";
        UUID mirrorId = seedAccount("mirror", mirrored, "CONSUMER", false);
        seedAuthUser(mirrored, "ROLE_CONSUMER");
        seedAssignment(mirrorId, "CONSUMER", false);
        Integer mirroredRows = jdbc.queryForObject(
                "select count(*) from auth_effective_authorities "
                        + "where username = ? and authority = 'ROLE_CONSUMER'",
                Integer.class, mirrored);
        assertThat(mirroredRows).as("UNION dedupes the mirrored authority to one row").isEqualTo(1);

        // (b) A soft-deleted membership carries nothing even while its
        // assignment row is still active.
        String withdrawn = "phase1-it-withdrawn@example.com";
        UUID withdrawnId = seedAccount("withdrawn", withdrawn, "CONSUMER", true);
        seedAssignment(withdrawnId, "ADMIN", false);
        Integer withdrawnRows = jdbc.queryForObject(
                "select count(*) from auth_effective_authorities where username = ?",
                Integer.class, withdrawn);
        assertThat(withdrawnRows).as("is_deleted = FALSE keeps the dead account out").isZero();

        // (c) The auth-only break-glass principal is untouched: no users
        // row, no assignments — exactly its own authority rows.
        String authOnly = "phase1-it-authonly@example.com";
        seedAuthUser(authOnly, "ROLE_ADMIN", "ROLE_OPERATOR");
        assertThat(authoritiesOf(authOnly))
                .as("the union adds nothing the login row does not back")
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OPERATOR");
    }

    // -- the closed vocabularies on the real schema -------------------------

    @Test
    void theClosedVocabulariesRefuseTheForbiddenStatesOnTheRealSchema() {
        String subject = "phase1-it-closed@example.com";
        UUID userId = seedAccount("closed", subject, "CONSUMER", false);

        // users.role survives untouched: NOT NULL, its V1 CHECK intact,
        // no widening — the Phase-1 letter («اترك users.role قائماً»).
        Map<String, Object> column = jdbc.queryForMap(
                "select is_nullable from information_schema.columns "
                        + "where table_name = 'users' and column_name = 'role'");
        assertThat(column.get("is_nullable")).as("users.role stays NOT NULL").isEqualTo("NO");
        Integer usersRoleCheck = jdbc.queryForObject(
                "select count(*) from information_schema.check_constraints "
                        + "where constraint_schema = current_schema() "
                        + "  and constraint_name = 'users_role_check'",
                Integer.class);
        assertThat(usersRoleCheck).as("users.role keeps its V1 CHECK").isEqualTo(1);

        // The role vocabulary stays closed — no role kind is invented.
        assertThatThrownBy(() -> jdbc.update(
                "insert into user_role_assignments (id, user_id, role, source) "
                        + "values (?, ?, 'SUPERUSER', 'ADMIN')", UUID.randomUUID(), userId))
                .as("the closed role CHECK refuses invented role kinds")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_role_assignments_role_check");

        // The §6.5 trust vocabulary stays closed — never one mega flag,
        // never an invented fifth type.
        assertThatThrownBy(() -> jdbc.update(
                "insert into verification_attestations (id, subject_user_id, trust_type, state, evidence_ref) "
                        + "values (?, ?, 'ONE_MEGA_VERIFIED_FLAG', 'PENDING', 'ev')",
                UUID.randomUUID(), userId))
                .as("the closed trust-type CHECK refuses conflation")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("verification_attestations_trust_type_check");

        assertThatThrownBy(() -> jdbc.update(
                "insert into verification_attestations (id, subject_user_id, trust_type, state, evidence_ref) "
                        + "values (?, ?, 'VERIFIED_BUSINESS', 'SUSPENDED', 'ev')",
                UUID.randomUUID(), userId))
                .as("the closed state machine refuses invented states")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("verification_attestations_state_check");

        // One GRANTED attestation per (subject, type) — the partial unique
        // index backs the service's read.
        jdbc.update("insert into verification_attestations "
                        + "(id, subject_user_id, trust_type, state, evidence_ref, granted_at, granted_by) "
                        + "values (?, ?, 'VERIFIED_BUSINESS', 'GRANTED', 'ev', now(), null)",
                UUID.randomUUID(), userId);
        assertThatThrownBy(() -> jdbc.update(
                "insert into verification_attestations "
                        + "(id, subject_user_id, trust_type, state, evidence_ref, granted_at, granted_by) "
                        + "values (?, ?, 'VERIFIED_BUSINESS', 'GRANTED', 'ev2', now(), null)",
                UUID.randomUUID(), userId))
                .as("a second GRANTED attestation of the same pair is refused")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_verification_attestations_one_active");

        // A REVOKED row frees the pair — the fresh request is legal.
        jdbc.update("update verification_attestations set state = 'REVOKED', revoked_at = now() "
                + "where subject_user_id = ? and trust_type = 'VERIFIED_BUSINESS'", userId);
        jdbc.update("insert into verification_attestations "
                        + "(id, subject_user_id, trust_type, state, evidence_ref) "
                        + "values (?, ?, 'VERIFIED_BUSINESS', 'PENDING', 'fresh-evidence')",
                UUID.randomUUID(), userId);

        // One PENDING request per (subject, type) — the one-review-at-a-time
        // backstop (the CodeRabbit #543 round): the service's read answers
        // the clean 409; the concurrent double-submit race dies here.
        assertThatThrownBy(() -> jdbc.update(
                "insert into verification_attestations "
                        + "(id, subject_user_id, trust_type, state, evidence_ref) "
                        + "values (?, ?, 'VERIFIED_BUSINESS', 'PENDING', 'twin')",
                UUID.randomUUID(), userId))
                .as("a second PENDING of the same pair is refused")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_verification_attestations_one_pending");

        // The decision frees the pair — a REJECTED row lets the fresh
        // request ride (the history keeps growing one review at a time).
        jdbc.update("update verification_attestations set state = 'REJECTED' "
                + "where subject_user_id = ? and trust_type = 'VERIFIED_BUSINESS'", userId);
        jdbc.update("insert into verification_attestations "
                        + "(id, subject_user_id, trust_type, state, evidence_ref) "
                        + "values (?, ?, 'VERIFIED_BUSINESS', 'PENDING', 'after-rejection')",
                UUID.randomUUID(), userId);
    }

    // -- the attestation lifecycle on the real tables -----------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void theAttestationLifecycleRidesTheRealTables() {
        String subject = "phase1-it-trust@example.com";
        UUID subjectId = seedAccount("trust", subject, "CONSUMER", false);

        // The self-service request — PENDING with its evidence (required:
        // the evidence IS the essence of «أدلة ارتباط»).
        var view = attestationService.request(
                subjectId, TrustType.VERIFIED_LOCAL_MEMBER, "geo:membership/phase1-it", "phase1-it-member");
        assertThat(view.state()).isEqualTo("PENDING");
        assertThat(view.grantedAt()).isNull();

        // The reviewer's decision records the grant stamp and the reviewer.
        var granted = attestationService.grant(view.id(), subjectId, "phase1-it-admin");
        assertThat(granted.state()).isEqualTo("GRANTED");
        assertThat(granted.grantedBy()).isEqualTo(subjectId);
        assertThat(granted.grantedAt()).isNotNull();

        // The withdrawal keeps the row and its history — the stamp, never
        // an erase.
        var revoked = attestationService.revoke(view.id(), "phase1-it-admin");
        assertThat(revoked.state()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAt()).isNotNull();

        // The re-review of a decided attestation is refused — the machine's
        // own words (the honest 409 surface rides the HTTP boundary; the
        // transition guard itself throws).
        assertThatThrownBy(() -> attestationService.grant(view.id(), subjectId, "phase1-it-admin"))
                .isInstanceOf(IllegalStateException.class);
    }
}
