package com.marketplace.app;

import com.marketplace.identity.RoleAssignmentService;
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
 * {@code JdbcUserDetailsManager#loadUserByUsername} reading the V183
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
