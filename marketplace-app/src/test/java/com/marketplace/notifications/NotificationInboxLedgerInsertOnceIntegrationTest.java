package com.marketplace.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The urgent-alert inbox's idempotent-insert CONTRACT, executed against
 * the REAL migration schema — the CatalogSearchFullTextIntegrationTest
 * discipline (spring.flyway.enabled=true + ddl-auto=none), because the
 * contract's two halves are migration-owned, not entity-owned:
 *
 * <ol>
 *   <li><b>The V180 ledger key</b> — the partial unique index
 *       {@code uq_notifications_source_event_once} on (recipient_id,
 *       source_event_id) WHERE source_event_id IS NOT NULL. A
 *       create-drop entity schema never creates it, so the duplicate's
 *       {@code ON CONFLICT DO NOTHING} absorption CANNOT happen there —
 *       the measured CI failure this class closes: the test lived in the
 *       module slice (Hibernate-generated schema), where the second
 *       insert answered 1 (no index to conflict with) after the 23502
 *       leg was fixed. The ledger law needs the schema that owns the
 *       ledger.</li>
 *   <li><b>The audit stamps and the born-complete row</b> — every NOT
 *       NULL column is BOUND by the statement (created_at, updated_at,
 *       is_deleted, version, is_read): on the migration schema V18/V25
 *       carry defaults for some of them, but the statement relies on
 *       NONE of them — it is honest on every schema the table can exist
 *       on (the Flyway chain AND the Hibernate-generated slices).</li>
 * </ol>
 *
 * <p>The module slice's own tests (NotificationsModuleIntegrationTest)
 * keep the entity-level behaviors (paging, unread counts, mark-read
 * metadata) on the create-drop schema — their contracts live in the
 * entity mapping. THIS class holds what only the migrated database can
 * prove.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NotificationInboxLedgerInsertOnceIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; the established IntegrationContainers factory shape.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The insert's full contract on the real migration chain (V18 + V25
     * + V180): the first (recipient, source event) insert wins (1), the
     * duplicate is absorbed by the partial unique index as a returned 0
     * (never a transaction-aborting 23505), exactly ONE row survives,
     * the legacy NULL-birth paths stay outside the ledger's constraint,
     * and the row is born complete — the audit stamps bound from the
     * caller's clock, the soft-delete flag FALSE, the optimistic-lock
     * version 0.
     */
    @Test
    void insertOnce_bindsEveryNotNullColumn_andTheLedgerKeyAbsorbsTheDuplicate() {
        UUID recipient = UUID.randomUUID();
        UUID sourceEvent = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-11T08:00:00Z");
        Instant updatedAt = Instant.parse("2026-10-11T08:00:05Z");

        int first = notificationRepository.insertOnce(UUID.randomUUID(), recipient,
                NotificationType.URGENT_ALERT.name(), "المصدر الرسمي: إغلاق طريق رئيسي",
                sourceEvent, createdAt, updatedAt);
        assertThat(first).as("the first (recipient, source event) insert wins").isEqualTo(1);

        int duplicate = notificationRepository.insertOnce(UUID.randomUUID(), recipient,
                NotificationType.URGENT_ALERT.name(), "المصدر الرسمي: إغلاق طريق رئيسي",
                sourceEvent, createdAt.plusSeconds(60), updatedAt.plusSeconds(60));
        assertThat(duplicate).as("the ledger key absorbs the re-delivery as a returned 0")
                .isZero();

        assertThat(notificationRepository.existsByRecipientIdAndSourceEventId(recipient,
                sourceEvent)).as("exactly ONE row survived the duplicate").isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ?",
                Integer.class, recipient)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? "
                        + "AND source_event_id IS NULL",
                Integer.class, recipient)).as("the legacy NULL-birth paths stay outside "
                        + "the ledger's constraint").isZero();

        java.sql.Timestamp createdStamp = jdbc.queryForObject(
                "SELECT created_at FROM notifications WHERE recipient_id = ?",
                java.sql.Timestamp.class, recipient);
        java.sql.Timestamp updatedStamp = jdbc.queryForObject(
                "SELECT updated_at FROM notifications WHERE recipient_id = ?",
                java.sql.Timestamp.class, recipient);
        assertThat(createdStamp.toInstant()).isEqualTo(createdAt);
        assertThat(updatedStamp.toInstant()).isEqualTo(updatedAt);

        // The born-complete row: the statement binds every NOT NULL column
        // itself — the soft-delete flag and the optimistic-lock version ride
        // as constants beside the audit stamps (no reliance on any database
        // default, honest on the Flyway chain and the Hibernate slices
        // alike — the 23502 closure's complete form).
        assertThat(jdbc.queryForObject(
                "SELECT is_deleted FROM notifications WHERE recipient_id = ?",
                Boolean.class, recipient)).as("the soft-delete column is born FALSE")
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM notifications WHERE recipient_id = ?",
                Long.class, recipient)).as("the optimistic-lock column is born 0")
                .isZero();
    }
}
