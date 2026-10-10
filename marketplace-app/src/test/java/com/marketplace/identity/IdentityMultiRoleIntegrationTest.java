package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import test.config.IntegrationContainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-03 (community platform execution plan Stage 1) — the role-SET contract
 * on the real PostgreSQL: the V182 backfill semantics (no privilege
 * escalation, the union preserves a drifted authority row, idempotent
 * re-run, unknown ROLE_ names skipped), the automatic registration grant,
 * and the grant/revoke end-to-end across all THREE stores (user_roles, the
 * users.role mirror, the auth_authorities projection) plus the Envers
 * audit trail.
 *
 * <p>The backfill leg re-executes V182's two INSERT statements verbatim
 * (their drift is impossible to miss: the checksum guard freezes the
 * migration and this javadoc names it) against freshly-planted legacy-shaped
 * rows — the statements' provenance comment ties them to the migration.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class IdentityMultiRoleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"})
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String ACTOR = "integration-admin";

    // ---- the V182 backfill contract ------------------------------------------

    @Test
    void backfill_grantsExactlyTheHeldRoles_noEscalation_noLoss() {
        UUID mirrorProvider = plantUser("d03-backfill-mirror", "PROVIDER");
        UUID drifted = plantUser("d03-backfill-drift", "CONSUMER");
        UUID wibble = plantUser("d03-backfill-wibble", "CONSUMER");
        jdbcTemplate.update(
                "INSERT INTO auth_authorities (username, authority) VALUES (?, 'ROLE_PROVIDER')",
                "d03-backfill-drift");
        jdbcTemplate.update(
                "INSERT INTO auth_authorities (username, authority) VALUES (?, 'ROLE_WIBBLE')",
                "d03-backfill-wibble");

        runBackfillStatements();

        // The mirror account: exactly its pre-existing role, provenance recorded.
        assertThat(roles("d03-backfill-mirror")).containsExactly(Map.of("role", "PROVIDER", "source", "LEGACY_MIRROR"));
        // The drifted account: the union — CONSUMER from the mirror AND the
        // ROLE_PROVIDER authority row. No loss, no escalation beyond what
        // the account already operationally held.
        assertThat(roles("d03-backfill-drift")).containsExactlyInAnyOrder(
                Map.of("role", "CONSUMER", "source", "LEGACY_MIRROR"),
                Map.of("role", "PROVIDER", "source", "LEGACY_AUTHORITIES"));
        // An authority outside the vocabulary is not a role.
        assertThat(roles("d03-backfill-wibble")).containsExactly(
                Map.of("role", "CONSUMER", "source", "LEGACY_MIRROR"));

        // The idempotent re-run answers no new rows.
        runBackfillStatements();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_roles WHERE user_id IN (?, ?, ?)",
                Integer.class, mirrorProvider, drifted, wibble)).isEqualTo(4);
    }

    // ---- the end-to-end role-set journey ---------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void registration_grant_revoke_moveAllThreeStoresAsOne() {
        User account = userService.register("d03-journey@b.com", "long-enough-pass", "D03 Journey");
        UUID id = account.getId();

        // The automatic registration grant: the SET is born with CONSUMER,
        // the login-side projection already carries its ROLE_ row.
        assertThat(userService.rolesOf(id)).containsExactly("CONSUMER");
        assertThat(jdbcTemplate.queryForList(
                "SELECT authority FROM auth_authorities WHERE username = ?", String.class,
                "d03-journey@b.com")).containsExactly("ROLE_CONSUMER");

        // The grant: the set row, the elevated mirror, and the projection
        // (now BOTH roles) land as one transaction; the audit trail records it.
        userService.grantRole(id, "PROVIDER", ACTOR);
        assertThat(userService.rolesOf(id)).containsExactlyInAnyOrder("CONSUMER", "PROVIDER");
        assertThat(userRepository.findById(id).orElseThrow().getRole()).isEqualTo(UserRole.PROVIDER);
        List<String> authorities = jdbcTemplate.queryForList(
                "SELECT authority FROM auth_authorities WHERE username = ? ORDER BY authority",
                String.class, "d03-journey@b.com");
        assertThat(authorities).containsExactly("ROLE_CONSUMER", "ROLE_PROVIDER");
        assertThat(auditAdds(id)).isGreaterThanOrEqualTo(2);

        // The mirror-follow is rank-derived: a de-escalating grant leaves the top role.
        userService.grantRole(id, "CONSUMER", ACTOR); // idempotent no-op — already held
        assertThat(userService.rolesOf(id)).containsExactlyInAnyOrder("CONSUMER", "PROVIDER");
        assertThat(userRepository.findById(id).orElseThrow().getRole()).isEqualTo(UserRole.PROVIDER);

        // The revoke: the set drops to the remaining role and the projection follows.
        userService.revokeRole(id, "CONSUMER", ACTOR);
        assertThat(userService.rolesOf(id)).containsExactly("PROVIDER");
        assertThat(userRepository.findById(id).orElseThrow().getRole()).isEqualTo(UserRole.PROVIDER);
        assertThat(jdbcTemplate.queryForList(
                "SELECT authority FROM auth_authorities WHERE username = ?", String.class,
                "d03-journey@b.com")).containsExactly("ROLE_PROVIDER");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revoke_refusesToEmptyTheSet_onTheRealStore() {
        User account = userService.register("d03-solo@b.com", "long-enough-pass", "D03 Solo");
        assertThat(userService.rolesOf(account.getId())).containsExactly("CONSUMER");

        assertThatThrownBy(() -> userService.revokeRole(account.getId(), "CONSUMER", ACTOR))
                .isInstanceOf(ConflictException.class);
        // The set is untouched.
        assertThat(userService.rolesOf(account.getId())).containsExactly("CONSUMER");
    }

    // ---- helpers -----------------------------------------------------------------

    private UUID plantUser(String subject, String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, subject, role) VALUES (?, ?, ?)", id, subject, role);
        return id;
    }

    /**
     * V182's backfill statements, verbatim (provenance: the checksum-frozen
     * migration — any drift here is a test failure away from a loud diff).
     */
    private void runBackfillStatements() {
        jdbcTemplate.execute("""
                insert into user_roles (id, user_id, role, granted_at, granted_by, source)
                select gen_random_uuid(), u.id, u.role::text, now(), 'SYSTEM', 'LEGACY_MIRROR'
                from users u
                on conflict (user_id, role) where is_deleted = false do nothing
                """);
        jdbcTemplate.execute("""
                insert into user_roles (id, user_id, role, granted_at, granted_by, source)
                select gen_random_uuid(), u.id, substring(a.authority from 6), now(), 'SYSTEM', 'LEGACY_AUTHORITIES'
                from auth_authorities a
                join users u on u.subject = a.username
                where a.authority like 'ROLE\\_%'
                  and substring(a.authority from 6) in ('CONSUMER','PROVIDER','ADMIN')
                on conflict (user_id, role) where is_deleted = false do nothing
                """);
    }

    private List<Map<String, Object>> roles(String subject) {
        return jdbcTemplate.queryForList("""
                SELECT ur.role, ur.source FROM user_roles ur
                JOIN users u ON u.id = ur.user_id
                WHERE u.subject = ? AND ur.is_deleted = false
                """, subject);
    }

    private int auditAdds(UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_roles_aud WHERE user_id = ? AND revtype = 0",
                Integer.class, userId);
        return count == null ? 0 : count;
    }
}
