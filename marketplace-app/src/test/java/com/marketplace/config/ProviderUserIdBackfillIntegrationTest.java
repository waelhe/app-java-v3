package com.marketplace.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Guard for V39 (Codex review fix C3, docs/codex-review-fixes-plan.md §3-C3):
 * the corrected provider_profiles.user_id backfill that (1) severs any link to
 * a soft-deleted user and (2) re-links NULL profiles to the single ACTIVE
 * (is_deleted = false) PROVIDER whose display_name matches — V23's criteria
 * plus is_deleted=false on both the join and the uniqueness subquery.
 *
 * <p>Follows the {@code AuditedWritesIntegrationTest} /
 * {@code DeadQuartzStoreRemovalIntegrationTest} pattern: real PostgreSQL with
 * Flyway enabled and {@code ddl-auto=none}, so V39 runs against exactly the
 * schema migrations produce (the {@code test} profile's
 * {@code ddl-auto: create-drop} is overridden — the lesson recorded three
 * times: an entity-generated schema hides what migrations actually do).
 *
 * <p><b>Why the guard re-runs V39's statements rather than reading a
 * pre-seeded state:</b> Flyway applies the whole migration chain (V39
 * included) at context startup, before any test method, so a test cannot
 * observe the world "before V39" on the migrated database. Two things are
 * therefore proven:
 * <ol>
 *   <li><b>Clean-start integrity:</b> a full startup with every migration
 *       (V39's DML included) succeeds — any DML error in V39 fails the context
 *       boot (the {@code AuditedWrites} reasoning).</li>
 *   <li><b>Semantics of the repair on the real schema:</b> this test seeds
 *       active + soft-deleted users and unlinked profiles, then executes the
 *       exact two statements of V39 verbatim (the sever, then the corrected
 *       backfill) and asserts the outcome: the deleted twin does not win the
 *       match, and no profile stays bound to a tombstone. Executing the
 *       migration text itself (read from the classpath migration file) is the
 *       honest guard — it cannot drift from the shipped script.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProviderUserIdBackfillIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established integration-test pattern (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Executes the V39 migration text (read verbatim from the classpath) so
     * the guard can never drift from the shipped script.
     */
    private void runV39() {
        String sql;
        try (var in = getClass().getResourceAsStream(
                "/db/migration/V39__repair_provider_user_id_backfill.sql")) {
            sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("V39 migration file not readable", e);
        }
        // Only the SQL statements matter: Flyway strips line comments before
        // executing. The V39 header comments legitimately contain ';' (e.g.
        // "…applied migration; ship a new V"), so we drop comment and blank
        // lines before splitting on the statement terminator, exactly as
        // Flyway's parser does. Otherwise a stray ';' inside a comment would
        // be misread as a statement boundary and emit a bare fragment.
        String statements = sql.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                .reduce("", (acc, line) -> acc + "\n" + line);
        for (String statement : statements.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement.strip());
            }
        }
    }

    private UUID insertUser(String subject, String displayName, String role, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role, is_deleted)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, subject, subject + "@example.com", displayName, role, deleted);
        return id;
    }

    private UUID insertProfile(UUID userId, String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at)
                VALUES (?, ?, 'c3-test', 'PENDING', ?, now(), now())
                """, id, displayName, userId);
        return id;
    }

    @BeforeEach
    void clearBefore() {
        jdbc.update("DELETE FROM provider_profiles WHERE bio = 'c3-test'");
    }

    @Test
    void backfillLinksOnlyTheActiveSingleMatch() {
        // A name-twin: one ACTIVE PROVIDER and one soft-deleted PROVIDER with
        // the same display_name. V23 would have seen both in its COUNT and
        // (without is_deleted on the subquery) suppressed the match; the
        // corrected backfill must ignore the deleted twin and link the active
        // one. We run the repair against these as the "before-V39" rows by
        // first setting user_id NULL (the post-sever state the backfill
        // targets).
        String display = "c3-active-provider";
        UUID active = insertUser("c3-active-provider-subject", display, "PROVIDER", false);
        insertUser("c3-deleted-twin-1", display, "PROVIDER", true);
        insertUser("c3-deleted-twin-2", display, "PROVIDER", true);

        UUID profile = insertProfile(null, display);

        runV39();

        // The two deleted name-twins must not poison the match: the profile is
        // linked to the single ACTIVE provider, not suppressed.
        UUID linked = jdbc.queryForObject(
                "SELECT user_id FROM provider_profiles WHERE id = ?", UUID.class, profile);
        assertThat(linked)
                .as("the profile must be backfilled to the single active PROVIDER, "
                        + "ignoring soft-deleted name-twins")
                .isEqualTo(active);
    }

    @Test
    void severClearsLinksToSoftDeletedUsers() {
        // A profile already linked (as V23 may have left it) to a soft-deleted
        // user must be severed to NULL.
        String display = "c3-sever-provider";
        UUID deleted = insertUser("c3-sever-provider-subject", display, "PROVIDER", true);
        UUID profile = insertProfile(deleted, display);

        runV39();

        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM provider_profiles WHERE id = ?", UUID.class, profile))
                .as("a profile bound to a soft-deleted user must be severed to NULL")
                .isNull();
    }

    @Test
    void noProfileIsBoundToASoftDeletedUserAfterMigration() {
        // On the clean migrated schema (V39 already applied at boot) no single
        // profile may reference a tombstone — the invariant V39 guarantees on
        // a fresh database. Proves the migration chain is stable (do no harm).
        Integer boundToDeleted = jdbc.queryForObject(
                "SELECT count(*) FROM provider_profiles pp"
                        + " JOIN users u ON u.id = pp.user_id"
                        + " WHERE u.is_deleted = true",
                Integer.class);
        assertThat(boundToDeleted)
                .as("no provider profile may reference a soft-deleted user after V39")
                .isZero();
    }
}
