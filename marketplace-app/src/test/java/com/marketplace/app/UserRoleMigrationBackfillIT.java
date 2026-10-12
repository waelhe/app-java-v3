package com.marketplace.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 (the unified plan §10 — D-03 multi-role) — the V187 backfill's
 * own acceptance proof, run against the REAL migration chain on an
 * isolated PostgreSQL: the backfill must give every LIVE account exactly
 * ONE active assignment mirroring its OWN {@code users.role} —
 * preserving the account's access without expanding it («الحسابات
 * القائمة تحفظ وصولها بلا توسيع صلاحيات», the Phase-1 gate) — and
 * nothing for the withdrawn (soft-deleted) memberships. The gateway's
 * other legs — the effective authorities view and the closed
 * vocabularies on the booted chain — are the {@code
 * UserRoleMigrationIntegrationTest}'s subject; this class proves the
 * WRITE the migration itself performs, so the backfill is exercised
 * exactly as production will experience it: the migration's own
 * statement over a pre-existing users population.
 *
 * <p><b>The chain rides Spring Boot's Flyway; the statement rides the
 * migration file itself</b> (the measured restructure, 2026-10-12): the
 * class's original shape ran the chain PROGRAMMATICALLY
 * (Flyway.configure()...target(181/latest)) — and hung deterministically
 * inside V51's {@code CREATE INDEX CONCURRENTLY} on two measured CI runs
 * (07:34Z and 13:38Z: the migration chain never advanced past "Migrating
 * schema public to version 51 [non-transactional]" while the runner sat
 * until its 6-hour ceiling). The SAME full chain — V51 and every other
 * CONCURRENTLY migration included — is proven green under Spring Boot's
 * autoconfigured Flyway on every CI run of this repository (the {@code
 * CatalogSearchFullTextIntegrationTest}/{@code
 * UserRoleMigrationIntegrationTest} house pattern: {@code
 * spring.flyway.enabled=true + ddl-auto=none}), so this class now rides
 * that mechanism for the schema. The backfill transition itself stays
 * honest: the test inserts the pre-wave accounts (no assignment rows —
 * the exact pre-V187 shape a live production database presents), then
 * executes V187's backfill statement <b>verbatim, read from the
 * migration file on the classpath</b> — the same INSERT..SELECT
 * production will run, not a re-implementation — and asserts the
 * no-expansion invariant over the result.</p>
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
class UserRoleMigrationBackfillIT {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; the established IntegrationContainers factory shape.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Instant OLD = Instant.parse("2024-03-01T10:00:00Z");

    private void insertUser(UUID id, String subject, String role, Instant createdAt,
                            boolean deleted) {
        jdbcTemplate.update(
                "insert into users (id, subject, email, display_name, role, is_deleted, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                id, subject, subject, "Account " + subject, role, deleted,
                Timestamp.from(createdAt));
    }

    /**
     * V187's backfill statement, VERBATIM from the migration file on the
     * classpath — the exact INSERT..SELECT production runs, not a
     * re-implementation. Line comments are stripped; the statement is
     * located by its INSERT keyword and cut at its terminating
     * semicolon (the migration file carries exactly one such statement).
     */
    private static String backfillStatement() {
        String path = "/db/migration/V187__user_role_assignments.sql";
        try (InputStream in = UserRoleMigrationBackfillIT.class.getResourceAsStream(path)) {
            assertThat(in).as("the migration file rides the classpath: %s", path).isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String stripped = String.join("\n",
                    sql.lines().filter(line -> !line.trim().startsWith("--")).toList());
            int start = stripped.indexOf("INSERT INTO user_role_assignments");
            assertThat(start).as("the backfill statement is present in the migration file")
                    .isNotNegative();
            int end = stripped.indexOf(';', start);
            assertThat(end).as("the backfill statement terminates").isGreaterThan(start);
            return stripped.substring(start, end + 1);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read the V187 migration file", failure);
        }
    }

    @Test
    void backfillMirrorsEveryLiveAccountsOwnRole_withoutAnyExpansion() {
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        // The pre-wave population: live accounts carrying their own
        // users.role and NO assignment rows — exactly the state a live
        // production database presents to V187 (the booted chain's own
        // backfill pass found an empty users table: seedContent=false).
        insertUser(consumerId, "backfill-consumer@example.com", "CONSUMER", OLD, false);
        insertUser(providerId, "backfill-provider@example.com", "PROVIDER", OLD.plusSeconds(60), false);
        insertUser(adminId, "backfill-admin@example.com", "ADMIN", OLD.plusSeconds(120), false);
        insertUser(deletedId, "backfill-deleted@example.com", "PROVIDER", OLD.plusSeconds(180), true);

        // The backfill itself — the migration's own statement, verbatim.
        jdbcTemplate.update(backfillStatement());

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
