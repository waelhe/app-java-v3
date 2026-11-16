package com.marketplace.knowledge;

import java.util.UUID;

import com.marketplace.knowledge.spi.KnowledgeContentPurgeAdapter;
import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The #762 lock-advance guard on real PostgreSQL — the CodeRabbit round
 * on #762's own nitpick, adopted as the structural follow-up it asked
 * for: the SPI unit test's mocked {@code JdbcTemplate} stubs every
 * update, so it proves the statement SHAPE only. This guard proves the
 * BEHAVIOR against the production schema — real Flyway V1..V156 on the
 * PostGIS container, {@code ddl-auto=none} (the V33 lesson: the NOT
 * NULL columns and the CHECK vocabulary are the real constraints the
 * tombstone convention is measured against).
 *
 * <p><b>The measured defect this guard keeps closed (the #762 rationale,
 * the adapter's own comment):</b> rows already carrying the tombstone
 * marker are excluded by the text update's filter, so a revise save
 * loaded against a pre-pass snapshot could still pass its
 * optimistic-lock check and resurrect content on a marker-carrying row.
 * The adapter's answer is the lock advance — {@code version = version + 1}
 * on the text filter's exact complement ({@code title = marker AND
 * body = marker}) BEFORE the text update: the tombstone's version moves
 * on EVERY pass. The purge invariant is a lock, not just a write. The
 * three legs this guard proves on the real database:
 * <ol>
 *   <li>the lock advance actually moves a marker-carrying row's
 *       {@code version} — +1 per pass, exactly once per pass (the mock
 *       could never show this);</li>
 *   <li>a save of the entity loaded BEFORE the pass — the stale
 *       snapshot, the defect's own shape — fails its optimistic-lock
 *       check: {@link ObjectOptimisticLockingFailureException}, the
 *       merge-time version comparison on BaseEntity's {@code @Version}
 *       (Spring Data JPA, Optimistic Locking);</li>
 *   <li>the re-run purges exactly zero text rows and returns 0 — the
 *       port's idempotence contract — while the lock still advances on
 *       every tombstone.</li>
 * </ol>
 *
 * <p><b>Why the raw-SQL seed (the Phase 1 pattern):</b> the rows are
 * seeded through {@code JdbcTemplate} INSERTs — the house seed
 * convention the purge guard established (bypassing Envers keeps the
 * mirror empty for these ids, so the text leg's count is exactly the
 * base rows). The marker is read from the port itself — {@link
 * AuthoredContentPurgePort#PURGED_MARKER}, the single constant every
 * adapter and every reader shares — never re-typed as a literal.
 *
 * <p><b>Official anchors (the pinned versions in the root POM):</b>
 * Spring Boot 4.1.2 — {@code @ServiceConnection} (Spring Boot reference,
 * Testing › Testcontainers: the annotated field contributes the {@code
 * ConnectionDetails} auto-configuration consumes); Testcontainers —
 * {@code disabledWithoutDocker} (the JUnit 5 extension's own contract:
 * the guard degrades to a skip where no Docker exists, never a red);
 * Spring Data JPA — optimistic locking via {@code @Version} (the
 * repository reference's documented failure shape:
 * {@code ObjectOptimisticLockingFailureException} on a stale save).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway (V1..V156), no
        // ddl-auto (the V33 lesson — the constraints under test are the
        // production constraints).
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // CI connection budget (the I5/L14 harness rationale — the same
        // numbers the purge guard carries).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class KnowledgePurgeLockAdvanceIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; the raw type matches IntegrationContainers' own return type (the established container pattern).
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    // The concrete adapter, not the port type: thirteen modules implement
    // AuthoredContentPurgePort (booking, catalog, community, disputes,
    // jobs, knowledge, messaging, notifications, provider, reviews,
    // search...) — by-type injection of the port would be ambiguous
    // (NoUniqueBeanDefinitionException); the adapter under guard is this
    // one, and it IS the port (the contract the guard exercises through
    // purgeAuthoredTexts).
    @Autowired
    private KnowledgeContentPurgeAdapter purgeAdapter;

    @Autowired
    private KnowledgeEntryRepository entries;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The acceptance sequence (one test — the legs share the state the
     * sequence produces, the purge guard's own pattern): seed a
     * marker-carrying row and a live-text row for two authors → load the
     * stale snapshot → first pass (the lock moves, the stale save fails,
     * the live text dies) → second pass (zero text rows, the lock still
     * moves — a lock, not a write).
     */
    @Test
    void fullSequence_lockAdvancesStaleSaveFailsRePurgeReturnsZero() {
        // The marker-carrying author: the row is BORN carrying the
        // tombstone (an author-submittable value — the #762 finding's own
        // scenario), so the text update never touches it; only the lock
        // advance does.
        UUID markerAuthor = UUID.randomUUID();
        UUID markerEntry = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO knowledge_entries (id, author_id, location_id, category, title, body) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                markerEntry, markerAuthor, UUID.randomUUID(), "TIPS",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);

        // The live-text author: the row the text update's filter matches.
        UUID textAuthor = UUID.randomUUID();
        UUID textEntry = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO knowledge_entries (id, author_id, location_id, category, title, body) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                textEntry, textAuthor, UUID.randomUUID(), "PLACES",
                "The neighborhood's best coffee corners",
                "The third-wave spots worth the walk.");

        // The stale snapshot, loaded BEFORE any pass — the #762 defect's
        // own shape: a revise save against a pre-pass snapshot.
        KnowledgeEntry stale = entries.findById(markerEntry).orElseThrow();
        assertThat(stale.getVersion())
                .as("the seeded row starts at V156's default version 0")
                .isZero();

        // ---- First pass, on the real database, through the real adapter.
        assertThat(purgeAdapter.purgeAuthoredTexts(markerAuthor))
                .as("a marker-carrying row text-purges nothing — the text "
                        + "filter's exact complement already matches; the port's "
                        + "count is text rows only")
                .isZero();

        // Leg 1 — the lock advance actually moved the version: +1 from the
        // lock statement, exactly once (the text update does not touch
        // this row). The mocked SPI test could never show this.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM knowledge_entries WHERE id = ?", Long.class, markerEntry))
                .as("the marker-carrying row's version advanced by exactly the lock pass")
                .isEqualTo(1L);

        // Leg 2 — the stale save fails its optimistic-lock check: the
        // resurrect hole the lock closes (Hibernate's merge-time version
        // comparison on BaseEntity's @Version).
        assertThatThrownBy(() -> entries.save(stale))
                .as("saving the pre-pass snapshot must fail — a stale revise "
                        + "would resurrect content the purge already locked")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // The lock is a lock, not a write: the text stays the tombstone.
        assertThat(entries.findById(markerEntry).orElseThrow().getTitle())
                .as("the lock advance rewrites nothing — the row's text is the tombstone it already was")
                .isEqualTo(AuthoredContentPurgePort.PURGED_MARKER);

        // The live-text author's first pass: the text dies on the real
        // database (base only — the raw seed leaves the Envers mirror
        // empty for this id, so the count is exactly the base row).
        assertThat(purgeAdapter.purgeAuthoredTexts(textAuthor))
                .as("the live-text row text-purges exactly one row on the first pass")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM knowledge_entries WHERE id = ?", String.class, textEntry))
                .as("the live text died into the tombstone")
                .isEqualTo(AuthoredContentPurgePort.PURGED_MARKER);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM knowledge_entries WHERE id = ?", Long.class, textEntry))
                .as("the text update bumps the version too (the review-round-1 fix: "
                        + "a JDBC update that leaves the version untouched can be "
                        + "silently reverted by a stale managed entity's flush)")
                .isEqualTo(1L);

        // Leg 3 — the re-run: zero text rows (the port's idempotence
        // contract), the lock still advancing on both tombstones.
        assertThat(purgeAdapter.purgeAuthoredTexts(textAuthor))
                .as("the re-run purges exactly zero text rows (the port contract: "
                        + "a re-run matches zero rows)")
                .isZero();
        assertThat(purgeAdapter.purgeAuthoredTexts(markerAuthor))
                .as("the marker author's re-run also returns zero")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM knowledge_entries WHERE id = ?", Long.class, textEntry))
                .as("the live-text tombstone's version moves on EVERY pass — "
                        + "2 after the second: the text update's +1 then the lock's +1")
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM knowledge_entries WHERE id = ?", Long.class, markerEntry))
                .as("the marker row: one lock pass per purge run — 2 after two runs")
                .isEqualTo(2L);
    }
}
