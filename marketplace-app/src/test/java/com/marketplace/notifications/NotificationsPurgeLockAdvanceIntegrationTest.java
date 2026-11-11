package com.marketplace.notifications;

import java.time.Instant;
import java.util.UUID;

import com.marketplace.notifications.spi.NotificationsContentPurgeAdapter;
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
 * The G4 lock-advance guard on real PostgreSQL — the #817 review round's
 * own promised independent follow-up (the house precedent: #761/#762
 * merged the knowledge family fix first, then #764 added exactly this
 * Testcontainers guard for the same defect). The SPI unit test's mocked
 * {@code JdbcTemplate} stubs every update, so it proves the statement
 * SHAPE only. This guard proves the BEHAVIOR against the production
 * schema — real Flyway V1..V188+ on the PostGIS container,
 * {@code ddl-auto=none} (the V33 lesson: the NOT NULL columns and the
 * partial unique index are the real constraints the purge convention is
 * measured against).
 *
 * <p><b>The measured defect this guard keeps closed (finding G4, the
 * 2026-11-10 adversarial behavioral review; fixed by batch 1 / #817):</b>
 * before #817 the LIVE purge statement left {@code notifications.version}
 * untouched, so a recipient's or admin's {@code markAsRead} transaction
 * holding a pre-purge {@code Notification} snapshot flushed its stale
 * version check successfully — {@code UPDATE ... WHERE id = ? AND version
 * = 0} matched the un-bumped row — silently restoring the erased message
 * and recreating it in a new {@code notifications_aud} revision. The
 * #817 answer is the family law (rulebook B5): the version advance rides
 * the SAME statement as the message erasure
 * ({@code version = COALESCE(version, 0) + 1}), so the tombstone's
 * version moves atomically with the erasure. The legs this guard proves
 * on the real database:
 * <ol>
 *   <li>the erasure and the version advance happen ATOMICALLY — the
 *       purged row's {@code version} moves to exactly 1 in the same
 *       statement (the mock could never show this);</li>
 *   <li>a {@code markAsRead} save of the entity loaded BEFORE the pass —
 *       the stale snapshot, the defect's own shape — fails its
 *       optimistic-lock check: {@link ObjectOptimisticLockingFailureException},
 *       the flush-time version comparison on BaseEntity's {@code @Version}
 *       (Spring Data JPA, Optimistic Locking) — instead of restoring the
 *       erased message through the passed check;</li>
 *   <li>the re-run purges exactly zero rows and returns 0 — the port's
 *       idempotence contract — and the version stays put (the family's
 *       single-statement shape: the row already carries the marker, the
 *       {@code message <> marker} filter excludes it from BOTH the
 *       erasure and the advance).</li>
 * </ol>
 *
 * <p><b>Why the raw-SQL seed (the Phase 1 / #764 pattern):</b> the rows
 * are seeded through {@code JdbcTemplate} INSERTs — the house seed
 * convention the purge guard established (bypassing Envers keeps the
 * {@code notifications_aud} mirror empty for these ids, so the port's
 * count is exactly the live row). Every NOT NULL column is BOUND, none
 * defaulted — the {@code insertOnce} discipline documented on
 * {@code NotificationRepository}: {@code created_at}/{@code updated_at}
 * carry no database default on the Flyway chain (the auditing listener
 * stamps them on JPA writes only), and {@code is_deleted}/{@code version}
 * must not rely on V25's defaults. The marker is read from the port
 * itself — {@link AuthoredContentPurgePort#PURGED_MARKER}, the single
 * constant every adapter and every reader shares — never re-typed as a
 * literal.</p>
 *
 * <p><b>Official anchors (the pinned versions in the root POM):</b>
 * Spring Boot 4.1.2 — {@code @ServiceConnection} (Spring Boot reference,
 * Testing › Testcontainers: the annotated field contributes the
 * {@code ConnectionDetails} auto-configuration consumes); Testcontainers
 * — {@code disabledWithoutDocker} (the JUnit 5 extension's own contract:
 * the guard degrades to a skip where no Docker exists, never a red);
 * Spring Data JPA — optimistic locking via {@code @Version} (the
 * repository reference's documented failure shape:
 * {@code ObjectOptimisticLockingFailureException} on a stale save).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway, no ddl-auto (the V33
        // lesson — the constraints under test are the production
        // constraints).
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // CI connection budget (the I5/L14 harness rationale — the same
        // numbers the purge guard carries).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NotificationsPurgeLockAdvanceIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; the raw type matches IntegrationContainers' own return type (the established container pattern).
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    // The concrete adapter, not the port type: thirteen modules implement
    // AuthoredContentPurgePort — by-type injection of the port would be
    // ambiguous (NoUniqueBeanDefinitionException); the adapter under
    // guard is this one, and it IS the port (the contract the guard
    // exercises through purgeAuthoredTexts).
    @Autowired
    private NotificationsContentPurgeAdapter purgeAdapter;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The acceptance sequence (one test — the legs share the state the
     * sequence produces, the #764 purge guard's own pattern): seed a
     * live-message row for the subject and a control row for a second
     * recipient → load the stale snapshot → first pass (the erasure and
     * the version advance land atomically, the stale markRead save fails,
     * the text dies into the tombstone, the other recipient's row stays
     * untouched) → second pass (zero rows, the version stays — a
     * re-run matches nothing).
     */
    @Test
    void fullSequence_erasureAndLockAdvanceAtomicallyStaleMarkReadFailsRePurgeReturnsZero() {
        // The subject's row: the live message the text update's filter
        // matches (the G4 defect's own scenario — a real feed row whose
        // message dies into the tombstone while its version moves).
        UUID recipient = UUID.randomUUID();
        UUID subjectRow = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO notifications (id, recipient_id, type, message, is_read, is_deleted, version, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, false, false, 0, ?, ?)",
                subjectRow, recipient, "BOOKING_CONFIRMED",
                "Your booking is confirmed — see you at the studio.", now, now);

        // The control row: a second recipient's live message — the purge
        // is recipient-scoped by the statement's WHERE, and this row
        // proves it on the real database (untouched text, untouched
        // version).
        UUID otherRecipient = UUID.randomUUID();
        UUID controlRow = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO notifications (id, recipient_id, type, message, is_read, is_deleted, version, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, false, false, 0, ?, ?)",
                controlRow, otherRecipient, "BOOKING_CONFIRMED",
                "A different recipient's delivery state entirely.", now, now);

        // The stale snapshot, loaded BEFORE the pass — the G4 defect's
        // own shape: a markAsRead transaction holding a pre-purge
        // Notification snapshot.
        Notification stale = notifications.findById(subjectRow).orElseThrow();
        assertThat(stale.getVersion())
                .as("the seeded row starts at V25's default version 0")
                .isZero();

        // ---- First pass, on the real database, through the real adapter.
        // The raw seed leaves the Envers mirror empty for these ids, so
        // the port's count is exactly the live row.
        assertThat(purgeAdapter.purgeAuthoredTexts(recipient))
                .as("the live-message row text-purges exactly one row on the first pass")
                .isEqualTo(1);

        // Leg 1 — the erasure and the version advance landed ATOMICALLY:
        // the purged row's version moved to exactly 1 in the same
        // statement (the statement-shape unit test can only assert the
        // SQL text; this is the behavior it stands for).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM notifications WHERE id = ?", Long.class, subjectRow))
                .as("the purged row's version advanced by exactly the erasure statement's own +1")
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message FROM notifications WHERE id = ?", String.class, subjectRow))
                .as("the live text died into the tombstone")
                .isEqualTo(AuthoredContentPurgePort.PURGED_MARKER);

        // Leg 2 — the stale markAsRead save fails its optimistic-lock
        // check: the resurrect hole the atomic advance closes (Hibernate's
        // flush-time version comparison on BaseEntity's @Version — the
        // UPDATE matches zero rows because the version already moved).
        stale.markRead();
        assertThatThrownBy(() -> notifications.save(stale))
                .as("saving the pre-pass snapshot must fail — a stale markAsRead "
                        + "would restore the erased message through the passed check")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // The recipient scoping on the real database: the second
        // recipient's row is untouched by the subject's purge (text
        // intact, version unmoved).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message FROM notifications WHERE id = ?", String.class, controlRow))
                .as("the purge is recipient-scoped — another recipient's row keeps its message")
                .isEqualTo("A different recipient's delivery state entirely.");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM notifications WHERE id = ?", Long.class, controlRow))
                .as("the control row's version is unmoved by the subject's purge")
                .isZero();

        // Leg 3 — the re-run: zero rows (the port's idempotence
        // contract), and the version stays put — the family's
        // single-statement shape: the tombstone-carrying row is excluded
        // from the statement by the message <> marker filter, from the
        // erasure AND the advance alike.
        assertThat(purgeAdapter.purgeAuthoredTexts(recipient))
                .as("the re-run purges exactly zero rows (the port contract: a re-run matches nothing)")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM notifications WHERE id = ?", Long.class, subjectRow))
                .as("the re-run does not move the tombstone's version — the single "
                        + "statement's filter excludes marker rows entirely")
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message FROM notifications WHERE id = ?", String.class, subjectRow))
                .as("the tombstone's text is unchanged by the re-run")
                .isEqualTo(AuthoredContentPurgePort.PURGED_MARKER);
    }
}
