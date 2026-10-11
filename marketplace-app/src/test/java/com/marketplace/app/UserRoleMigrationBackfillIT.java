package com.marketplace.app;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 (the unified plan §10 — D-03 multi-role) — the V182 backfill's
 * own acceptance proof, run against the REAL migration chain on an
 * isolated PostgreSQL (the {@code AuthorizedClientPersistenceIT}
 * programmatic-Flyway shape, applied to the app's own {@code db/migration}
 * tree): the backfill must give every LIVE account exactly ONE active
 * assignment mirroring its OWN {@code users.role} — preserving the
 * account's access without expanding it («الحسابات القائمة تحفظ وصولها
 * بلا توسيع صلاحيات», the Phase-1 gate) — and nothing for the withdrawn
 * (soft-deleted) memberships. The gateway's other legs — the effective
 * authorities view and the closed vocabularies on the booted chain — are
 * the {@code UserRoleMigrationIntegrationTest}'s subject; this class
 * proves the WRITE the migration itself performs, so the backfill is
 * exercised exactly as production will experience it: rows inserted by
 * V182 over a pre-existing users population.
 *
 * <p>The two-phase migrate is the official Flyway API doing exactly what
 * the plan's Phase-0 checklist prescribes for an upgrade-path check: apply
 * the chain UP TO V181 (the pre-wave head), populate the pre-existing
 * accounts, then apply the wave (V182..V184) on top — the same order the
 * live production database will replay.
 */
@Testcontainers(disabledWithoutDocker = true)
class UserRoleMigrationBackfillIT {

    @Container
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"));

    private static JdbcTemplate jdbcTemplate;

    private static final Instant OLD = Instant.parse("2024-03-01T10:00:00Z");

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                // The application-test.yml posture for the content seed —
                // the trial-zone content stays out of every test database.
                .placeholders(java.util.Map.of("seedContent", "false"))
                .target(target)
                .load()
                .migrate();
        if (jdbcTemplate == null) {
            jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        }
    }

    private static void insertUser(UUID id, String subject, String role, Instant createdAt,
                                   boolean deleted) {
        jdbcTemplate.update(
                "insert into users (id, subject, email, display_name, role, is_deleted, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                id, subject, subject, "Account " + subject, role, deleted,
                Timestamp.from(createdAt));
    }

    @Test
    void backfillMirrorsEveryLiveAccountsOwnRole_withoutAnyExpansion() {
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        migrateTo("181");
        insertUser(consumerId, "backfill-consumer@example.com", "CONSUMER", OLD, false);
        insertUser(providerId, "backfill-provider@example.com", "PROVIDER", OLD.plusSeconds(60), false);
        insertUser(adminId, "backfill-admin@example.com", "ADMIN", OLD.plusSeconds(120), false);
        insertUser(deletedId, "backfill-deleted@example.com", "PROVIDER", OLD.plusSeconds(180), true);

        migrateTo("latest");

        // Exactly ONE active assignment per live account, and it mirrors
        // its OWN users.role — the no-expansion invariant is a JOIN: no
        // assignment may claim a role the account's row does not carry
        // (scoped to this test's own pre-migration population, the same
        // shape the production replay would present).
        List<Map<String, Object>> mismatches = jdbcTemplate.queryForList(
                "select u.id, u.role as user_role, ra.role as assigned_role "
                        + "from users u "
                        + "left join user_role_assignments ra "
                        + "  on ra.user_id = u.id and ra.revoked_at is null "
                        + "where u.id in (?, ?, ?, ?) "
                        + "  and (ra.id is null or ra.role <> u.role)",
                consumerId, providerId, adminId, deletedId);
        assertThat(mismatches).as("every live account holds exactly its own role").isEmpty();

        // The soft-deleted membership carries nothing — there is no access
        // left to preserve.
        Integer deletedRows = jdbcTemplate.queryForObject(
                "select count(*) from user_role_assignments where user_id = ?",
                Integer.class, deletedId);
        assertThat(deletedRows).as("withdrawn memberships are not backfilled").isZero();

        // The backfill's own provenance, per the migration's header. The
        // birthday comparison rides timestamptz equality in the database —
        // the same instant, whatever zone the driver renders it in.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select ra.role, ra.granted_by, ra.source, ra.revoked_at, "
                        + "(ra.granted_at = u.created_at) as granted_at_is_the_birthday "
                        + "from user_role_assignments ra join users u on u.id = ra.user_id "
                        + "where u.id in (?, ?, ?) order by u.subject",
                consumerId, providerId, adminId);
        assertThat(rows).hasSize(3);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("granted_by")).as("the migration is a system act").isNull();
            assertThat(row.get("source")).isEqualTo("BACKFILL");
            assertThat(row.get("revoked_at")).as("backfilled assignments are ACTIVE").isNull();
            assertThat(row.get("granted_at_is_the_birthday"))
                    .as("granted_at is the account's own birthday")
                    .isEqualTo(Boolean.TRUE);
        }
        assertThat(rows.stream().map(r -> r.get("role")))
                .containsExactly("CONSUMER", "PROVIDER", "ADMIN");
    }

    @Test
    void theClosedVocabularyAndTheOneActivePairAreEnforcedOnTheRealTable() {
        UUID userId = UUID.randomUUID();
        migrateTo("latest");
        insertUser(userId, "constraint-account@example.com", "CONSUMER", OLD, false);

        // The role CHECK carries exactly the closed V1 vocabulary — the
        // task's constraint: no role kind is invented in this phase.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into user_role_assignments (id, user_id, role, source) "
                        + "values (?, ?, 'SUPERUSER', 'ADMIN')", UUID.randomUUID(), userId))
                .as("the closed role CHECK refuses invented role kinds")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_role_assignments_role_check");

        // The source CHECK is the row's two legitimate writers.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into user_role_assignments (id, user_id, role, source) "
                        + "values (?, ?, 'PROVIDER', 'SELF_SERVICE')", UUID.randomUUID(), userId))
                .as("the closed source CHECK refuses unknown writers")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_role_assignments_source_check");

        // One ACTIVE assignment per (user, role) — the partial unique
        // index is the concurrent-insert backstop. Granting beyond the
        // primary role IS the feature (the CHECK bounds the vocabulary,
        // not the combination).
        jdbcTemplate.update(
                "insert into user_role_assignments (id, user_id, role, source) "
                        + "values (?, ?, 'PROVIDER', 'ADMIN')", UUID.randomUUID(), userId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into user_role_assignments (id, user_id, role, source) "
                        + "values (?, ?, 'PROVIDER', 'ADMIN')", UUID.randomUUID(), userId))
                .as("a second ACTIVE assignment of the same pair is refused")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_user_role_assignments_one_active");

        // Revoking frees the pair: the fresh row is a new grant, the
        // history stays.
        jdbcTemplate.update(
                "update user_role_assignments set revoked_at = now() "
                        + "where user_id = ? and role = 'PROVIDER'", userId);
        jdbcTemplate.update(
                "insert into user_role_assignments (id, user_id, role, source) "
                        + "values (?, ?, 'PROVIDER', 'ADMIN')", UUID.randomUUID(), userId);
        Integer activeCount = jdbcTemplate.queryForObject(
                "select count(*) from user_role_assignments "
                        + "where user_id = ? and role = 'PROVIDER' and revoked_at is null",
                Integer.class, userId);
        assertThat(activeCount).as("exactly one ACTIVE row after the re-grant").isEqualTo(1);
    }
}
