package com.marketplace.availability;

import test.config.IntegrationContainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Migration-time guard for V80's data repair (CodeRabbit round 2 on PR #471,
 * adopted from the root): the reconciliation is the highest-stakes SQL of the
 * R2+R3 wave — it converges production data at deploy time, and the regular
 * post-boot integration tests can never see it (by the time the context is
 * up, Flyway has already applied V80 to a fresh database and the one-time
 * repair had nothing to transform).
 *
 * <p>This test boots with {@code spring.flyway.target=79} — the schema stops
 * BEFORE the repair migration (V79's ownership column exists; V80 has not
 * run) — seeds the exact pre-fix states the reconciliation exists for, then
 * executes the actual V80 script (the real file from the classpath, through
 * Spring's official {@link ScriptUtils} on an autocommit connection, matching
 * the migration's own non-transactional execution shape) and asserts the
 * converged outcomes:
 *
 * <ol>
 *   <li><b>The duplicate-claims window (the CodeRabbit round-1 finding's own
 *       scenario):</b> two live duplicate rows for one window, the older one
 *       booked — and TWO active bookings on that window (both CONFIRMED —
 *       the pre-fix corruption the R2 defect made reachable). After the
 *       repair: exactly ONE live row survives (the oldest), it is booked, and
 *       its owner is the LATEST-updated active claim — the deterministic
 *       total order; the younger duplicate is soft-deleted.</li>
 *   <li><b>The orphaned-hold window (the R2 residue):</b> a booked live row
 *       whose only booking is CANCELLED (the pre-fix cancel released the
 *       wrong duplicate and left the mate booked). After the repair: the
 *       row is REOPENED — booked = false, no owner — instead of staying
 *       booked forever with no booking able to release it.</li>
 *   <li><b>The backstop index:</b> the script's CONCURRENTLY build completed
 *       — the index exists UNIQUE and VALID on the migrated database.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // stop BEFORE the wave's repair migration — the pre-state this test
        // seeds is exactly the state V80 exists to converge
        "spring.flyway.target=79",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AvailabilitySlotReconciliationMigrationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void v73_reconcilesDuplicateClaims_reopensOrphanedHolds_buildsTheIndex() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();

        // The FK chain bookings needs: one provider user, one consumer user,
        // one listing. subject is users' unique key; the rest ride defaults.
        jdbcTemplate.update(
                "INSERT INTO users (id, subject, role) VALUES (?, ?, 'PROVIDER'), (?, ?, 'CONSUMER')",
                providerId, "provider-" + providerId, consumerId, "consumer-" + consumerId);
        jdbcTemplate.update(
                "INSERT INTO provider_listings (id, provider_id, title, category, price_cents, currency)"
                        + " VALUES (?, ?, 'migration test listing', 'STAY', 1000, 'SAR')",
                listingId, providerId);

        // ---- Window 1: the duplicate-claims corruption (round-1 finding) ----
        // X = the OLDER live row, booked, owned by bookingB (a stale claim
        // value on the row); Y = the younger OPEN duplicate.
        Instant w1Start = Instant.parse("2026-09-25T09:00:00Z");
        Instant w1End = Instant.parse("2026-09-25T10:00:00Z");
        UUID slotX = UUID.randomUUID();
        UUID slotY = UUID.randomUUID();
        UUID bookingA = UUID.randomUUID(); // CONFIRMED earlier — the older active claim
        UUID bookingB = UUID.randomUUID(); // CONFIRMED later — the claim that actually holds
        insertBooking(bookingA, consumerId, providerId, listingId, w1Start, w1End,
                "CONFIRMED", "2026-09-20 10:00:00");
        insertBooking(bookingB, consumerId, providerId, listingId, w1Start, w1End,
                "CONFIRMED", "2026-09-21 10:00:00");
        insertSlot(slotX, providerId, w1Start, w1End, true, bookingA, "2026-09-01 09:00:00");
        insertSlot(slotY, providerId, w1Start, w1End, false, null, "2026-09-02 09:00:00");

        // ---- Window 2: the orphaned hold (the R2 residue) ----
        Instant w2Start = Instant.parse("2026-09-26T09:00:00Z");
        Instant w2End = Instant.parse("2026-09-26T10:00:00Z");
        UUID slotZ = UUID.randomUUID();
        UUID cancelledBooking = UUID.randomUUID();
        insertBooking(cancelledBooking, consumerId, providerId, listingId, w2Start, w2End,
                "CANCELLED", "2026-09-20 10:00:00");
        insertSlot(slotZ, providerId, w2Start, w2End, true, cancelledBooking, "2026-09-01 09:00:00");

        // ---- execute the actual V80 script (the real file, non-transactional
        // shape: autocommit connection, statement by statement) ----
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V80__availability_slots_window_unique.sql"));
        }

        // ---- Window 1 outcomes ----
        Integer liveRowsW1 = liveSlotCount(providerId, w1Start, w1End);
        assertThat(liveRowsW1).as("exactly one live row survives the duplicate merge").isEqualTo(1);

        Boolean w1Booked = slotBooked(slotX);
        UUID w1Owner = slotHolder(slotX);
        assertThat(w1Booked).as("the surviving window stays booked (an active claim holds it)").isTrue();
        assertThat(w1Owner)
                .as("the owner is the LATEST-updated active claim — the deterministic pick, not the")
                .isEqualTo(bookingB);
        Boolean yDeleted = jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM availability_slots WHERE id = ?", Boolean.class, slotY);
        assertThat(yDeleted).as("the younger duplicate is soft-deleted, history intact").isTrue();

        // ---- Window 2 outcomes ----
        Boolean w2Booked = slotBooked(slotZ);
        UUID w2Owner = slotHolder(slotZ);
        assertThat(w2Booked)
                .as("an orphaned hold (no active booking) is reopened, never stuck booked")
                .isFalse();
        assertThat(w2Owner).as("the reopened row carries no owner").isNull();

        // ---- the backstop index completed its concurrent build ----
        Boolean[] index = jdbcTemplate.queryForObject(
                "SELECT i.indisvalid, i.indisunique FROM pg_index i"
                        + " JOIN pg_class c ON i.indexrelid = c.oid"
                        + " WHERE c.relname = 'uq_availability_slots_live_window'",
                (rs, rowNum) -> new Boolean[] { rs.getBoolean(1), rs.getBoolean(2) });
        assertThat(index).isNotNull();
        assertThat(index[0]).as("the CONCURRENTLY build completed (not INVALID)").isTrue();
        assertThat(index[1]).as("the index enforces uniqueness").isTrue();
    }

    // ---------- seeding helpers (explicit timestamps: the merge's "oldest"
    // and the reconciliation's "latest-updated" are total orders) ----------

    private void insertBooking(UUID id, UUID consumerId, UUID providerId, UUID listingId,
                               Instant startsAt, Instant endsAt, String status, String updatedAt) {
        jdbcTemplate.update(
                "INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents,"
                        + " currency, starts_at, ends_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 1000, 'SAR', ?, ?, ?::timestamptz, ?::timestamptz)",
                id, consumerId, providerId, listingId, status,
                Timestamp.from(startsAt), Timestamp.from(endsAt), updatedAt, updatedAt);
    }

    private void insertSlot(UUID id, UUID providerId, Instant startsAt, Instant endsAt,
                            boolean booked, UUID holder, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO availability_slots (id, provider_id, starts_at, ends_at, booked,"
                        + " held_by_booking_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
                id, providerId, Timestamp.from(startsAt), Timestamp.from(endsAt), booked, holder,
                createdAt, createdAt);
    }

    private Integer liveSlotCount(UUID providerId, Instant startsAt, Instant endsAt) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM availability_slots"
                        + " WHERE provider_id = ? AND starts_at = ? AND ends_at = ? AND is_deleted = false",
                Integer.class, providerId, Timestamp.from(startsAt), Timestamp.from(endsAt));
    }

    private Boolean slotBooked(UUID slotId) {
        return jdbcTemplate.queryForObject(
                "SELECT booked FROM availability_slots WHERE id = ?", Boolean.class, slotId);
    }

    private UUID slotHolder(UUID slotId) {
        return jdbcTemplate.queryForObject(
                "SELECT held_by_booking_id FROM availability_slots WHERE id = ?", UUID.class, slotId);
    }
}
