package com.marketplace.payments;

import test.config.IntegrationContainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Migration-time guard for V81's data repair (the R4 wave — one collectible
 * attempt per booking): the repair converges production data at deploy time,
 * and the regular post-boot integration tests can never see it (by the time
 * the context is up, Flyway has already applied V81 to a fresh database and
 * the one-time repair had nothing to transform).
 *
 * <p>This test boots with {@code spring.flyway.target=70} — the schema stops
 * BEFORE the repair migration, at the highest version that EXISTS on this
 * branch (V81 has not run; the wave-train's reserved numbers V71-V80
 * belong to unmerged wave branches, and Flyway rejects a target that does
 * not resolve to an existing migration file) — seeds the exact pre-fix
 * states the repair exists for, then executes the actual V81 script (the
 * real file from the classpath, through Spring's official {@link ScriptUtils}
 * on an autocommit connection, matching the migration's own
 * non-transactional execution shape) and asserts the converged outcomes:
 *
 * <ol>
 *   <li><b>The stale-retry booking:</b> two CREATED intents, no money ever
 *       moved — the LATEST attempt survives as the booking's one
 *       collectible intent; the older stale retry is CANCELLED (the state
 *       machine's own terminal state for an abandoned attempt).</li>
 *   <li><b>The double-charge window (the defect's worst case):</b> a
 *       SUCCEEDED intent (the booking is PAID) riding next to later
 *       siblings — a CREATED one (cancelled — cancelUnpaid's own mapping)
 *       and an in-flight PROCESSING one carrying a PENDING payment row
 *       (failed with its payment synced — failInFlight's own mapping,
 *       TRANSITIONS admits no PROCESSING -> CANCELLED; CodeRabbit round 1
 *       on this PR).</li>
 *   <li><b>The stale in-flight retry:</b> an older PROCESSING attempt with
 *       a PENDING payment under a latest CREATED attempt — the older one
 *       fails (intent and payment together), the latest survives.</li>
 *   <li><b>Single-attempt and terminal rows are untouched:</b> a lone
 *       CREATED attempt keeps its state; FAILED / CANCELLED history keeps
 *       its state.</li>
 *   <li><b>The backstop index:</b> the CONCURRENTLY build completed —
 *       UNIQUE and VALID — and a second collectible row for one booking is
 *       rejected with 23505 by the database itself.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // stop BEFORE the wave's repair migration, at the highest version
        // that EXISTS on this branch (the wave-train's reserved V71-V80
        // belong to unmerged wave branches; Flyway rejects a target that
        // resolves to no migration file) — the pre-state this test seeds is
        // exactly the state V81 exists to converge
        "spring.flyway.target=70",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PaymentIntentOneActiveAttemptMigrationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void v74_cancelsStaleRetries_closesTheDoubleChargeWindow_buildsTheIndex() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();

        // The FK chain payment_intents needs: one provider user, one
        // consumer user, one listing. subject is users' unique key.
        jdbcTemplate.update(
                "INSERT INTO users (id, subject, role) VALUES (?, ?, 'PROVIDER'), (?, ?, 'CONSUMER')",
                providerId, "provider-" + providerId, consumerId, "consumer-" + consumerId);
        jdbcTemplate.update(
                "INSERT INTO provider_listings (id, provider_id, title, category, price_cents, currency)"
                        + " VALUES (?, ?, 'migration test listing', 'STAY', 1000, 'SAR')",
                listingId, providerId);

        // ---- Booking 1: the stale retry (two CREATED, nothing collected) ----
        UUID booking1 = UUID.randomUUID();
        insertBooking(booking1, consumerId, providerId, listingId);
        UUID olderRetry = UUID.randomUUID();
        UUID latestAttempt = UUID.randomUUID();
        insertIntent(olderRetry, booking1, consumerId, "CREATED", "2026-09-20 10:00:00");
        insertIntent(latestAttempt, booking1, consumerId, "CREATED", "2026-09-21 10:00:00");

        // ---- Booking 2: the double-charge window (PAID + later siblings —
        // a CREATED one AND an in-flight PROCESSING one carrying a PENDING
        // payment row) ----
        UUID booking2 = UUID.randomUUID();
        insertBooking(booking2, consumerId, providerId, listingId);
        UUID paidIntent = UUID.randomUUID();
        UUID doubleChargeSibling = UUID.randomUUID();
        UUID inFlightSibling = UUID.randomUUID();
        UUID inFlightPayment = UUID.randomUUID();
        insertIntent(paidIntent, booking2, consumerId, "SUCCEEDED", "2026-09-20 10:00:00");
        insertIntent(doubleChargeSibling, booking2, consumerId, "CREATED", "2026-09-22 10:00:00");
        insertIntent(inFlightSibling, booking2, consumerId, "PROCESSING", "2026-09-23 10:00:00");
        insertPayment(inFlightPayment, inFlightSibling, "PENDING");

        // ---- Booking 3: a lone live attempt (nothing to repair) ----
        UUID booking3 = UUID.randomUUID();
        insertBooking(booking3, consumerId, providerId, listingId);
        UUID loneAttempt = UUID.randomUUID();
        insertIntent(loneAttempt, booking3, consumerId, "PROCESSING", "2026-09-20 10:00:00");

        // ---- Booking 4: terminal history (nothing to repair) ----
        UUID booking4 = UUID.randomUUID();
        insertBooking(booking4, consumerId, providerId, listingId);
        UUID failedHistory = UUID.randomUUID();
        insertIntent(failedHistory, booking4, consumerId, "FAILED", "2026-09-19 10:00:00");
        UUID cancelledHistory = UUID.randomUUID();
        insertIntent(cancelledHistory, booking4, consumerId, "CANCELLED", "2026-09-18 10:00:00");

        // ---- Booking 5: the stale-retry repair with an IN-FLIGHT older
        // sibling (no money ever collected; the latest attempt is CREATED,
        // the older one is PROCESSING with a PENDING payment row) ----
        UUID booking5 = UUID.randomUUID();
        insertBooking(booking5, consumerId, providerId, listingId);
        UUID olderInFlight = UUID.randomUUID();
        UUID olderInFlightPayment = UUID.randomUUID();
        UUID latestAttempt5 = UUID.randomUUID();
        insertIntent(olderInFlight, booking5, consumerId, "PROCESSING", "2026-09-20 10:00:00");
        insertPayment(olderInFlightPayment, olderInFlight, "PENDING");
        insertIntent(latestAttempt5, booking5, consumerId, "CREATED", "2026-09-24 10:00:00");

        // ---- execute the actual V81 script (the real file,
        // non-transactional shape: autocommit connection) ----
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V81__payment_intents_one_active_attempt.sql"));
        }

        // ---- Booking 1 outcomes: the latest attempt is THE attempt ----
        assertThat(intentStatus(olderRetry))
                .as("the older stale retry is cancelled — the state machine's own terminal state")
                .isEqualTo("CANCELLED");
        assertThat(intentStatus(latestAttempt))
                .as("the latest attempt survives as the booking's one collectible intent")
                .isEqualTo("CREATED");

        // ---- Booking 2 outcomes: the double-charge window is closed ----
        assertThat(intentStatus(paidIntent))
                .as("the collected intent keeps its state — it is the booking's money truth")
                .isEqualTo("SUCCEEDED");
        assertThat(intentStatus(doubleChargeSibling))
                .as("the post-payment CREATED sibling is cancelled — cancelUnpaid's own mapping")
                .isEqualTo("CANCELLED");
        assertThat(intentStatus(inFlightSibling))
                .as("the post-payment PROCESSING sibling is FAILED — failInFlight's own mapping "
                        + "(TRANSITIONS admits no PROCESSING -> CANCELLED; CodeRabbit round 1)")
                .isEqualTo("FAILED");
        assertThat(paymentStatus(inFlightPayment))
                .as("the in-flight sibling's payment row follows its intent in the same statement")
                .isEqualTo("FAILED");

        // ---- Bookings 3, 4 and 5: the lone live attempt and terminal
        // history untouched; the stale-retry repair maps by state machine ----
        assertThat(intentStatus(loneAttempt)).isEqualTo("PROCESSING");
        assertThat(intentStatus(failedHistory)).isEqualTo("FAILED");
        assertThat(intentStatus(cancelledHistory)).isEqualTo("CANCELLED");
        assertThat(intentStatus(olderInFlight))
                .as("the stale in-flight retry is FAILED — failInFlight's mapping")
                .isEqualTo("FAILED");
        assertThat(paymentStatus(olderInFlightPayment))
                .as("its payment row follows")
                .isEqualTo("FAILED");
        assertThat(intentStatus(latestAttempt5))
                .as("the latest attempt is THE attempt — the total order alone decides")
                .isEqualTo("CREATED");

        // ---- the backstop index completed its concurrent build ----
        Boolean[] index = jdbcTemplate.queryForObject(
                "SELECT i.indisvalid, i.indisunique FROM pg_index i"
                        + " JOIN pg_class c ON i.indexrelid = c.oid"
                        + " WHERE c.relname = 'uq_payment_intents_one_active_attempt'",
                (rs, rowNum) -> new Boolean[] { rs.getBoolean(1), rs.getBoolean(2) });
        assertThat(index).isNotNull();
        assertThat(index[0]).as("the CONCURRENTLY build completed (not INVALID)").isTrue();
        assertThat(index[1]).as("the index enforces uniqueness").isTrue();

        // ---- and the database itself rejects a second collectible row ----
        UUID intruder = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                    "INSERT INTO payment_intents (id, booking_id, consumer_id, amount_cents, currency, status)"
                            + " VALUES (?, ?, ?, 1000, 'SAR', 'CREATED')",
                    intruder, booking1, consumerId);
            throw new AssertionError("the partial unique index must reject a second collectible attempt");
        } catch (DataIntegrityViolationException expected) {
            // 23505 wrapped in Spring's translation — the V67 house shape
        }

        // ---- while a terminal sibling never blocks a fresh attempt ----
        UUID retryAfterTerminal = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO payment_intents (id, booking_id, consumer_id, amount_cents, currency, status)"
                        + " VALUES (?, ?, ?, 1000, 'SAR', 'CREATED')",
                retryAfterTerminal, booking4, consumerId);
        assertThat(intentStatus(retryAfterTerminal))
                .as("the model's own allowance: a new attempt after FAILED/CANCELLED")
                .isEqualTo("CREATED");
    }

    // ---------- seeding helpers (explicit timestamps: the repair's "latest"
    // is the total order (created_at, id)) ----------

    private void insertBooking(UUID id, UUID consumerId, UUID providerId, UUID listingId) {
        jdbcTemplate.update(
                "INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents,"
                        + " currency, starts_at, ends_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'CONFIRMED', 1000, 'SAR',"
                        + " '2026-10-01 09:00:00+00', '2026-10-01 10:00:00+00', now(), now())",
                id, consumerId, providerId, listingId);
    }

    private void insertIntent(UUID id, UUID bookingId, UUID consumerId, String status, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payment_intents (id, booking_id, consumer_id, amount_cents, currency,"
                        + " status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 1000, 'SAR', ?, ?::timestamptz, ?::timestamptz)",
                id, bookingId, consumerId, status, createdAt, createdAt);
    }

    private void insertPayment(UUID id, UUID intentId, String status) {
        jdbcTemplate.update(
                "INSERT INTO payments (id, payment_intent_id, amount_cents, status, created_at, updated_at)"
                        + " VALUES (?, ?, 1000, ?, '2026-09-21 10:00:00+00', '2026-09-21 10:00:00+00')",
                id, intentId, status);
    }

    private String intentStatus(UUID intentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_intents WHERE id = ?", String.class, intentId);
    }

    private String paymentStatus(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }
}
