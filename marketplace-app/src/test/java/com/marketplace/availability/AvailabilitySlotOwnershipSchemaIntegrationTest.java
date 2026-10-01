package com.marketplace.availability;

import test.config.IntegrationContainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * End-to-end guard for the R2 + R3 wave (comprehensive-review-ar-fix plan
 * §4/R2 + §4/R3), on the <b>real Flyway schema</b> (the lesson recorded
 * three times: a test schema built by {@code ddl-auto: create-drop} hides
 * what production actually runs — V72's ownership column, V73's partial
 * unique index). Boots the full context on a real PostgreSQL with
 * {@code spring.flyway.enabled=true} + {@code ddl-auto=none} (the
 * {@code DeadQuartzStoreRemovalIntegrationTest} pattern).
 *
 * <p>Guards the wave's four invariants:
 * <ol>
 *   <li><b>Schema (V72):</b> {@code availability_slots.held_by_booking_id}
 *       exists on the table AND its Envers mirror {@code availability_slots_aud}.</li>
 *   <li><b>Schema (V73):</b> {@code uq_availability_slots_live_window} exists,
 *       is UNIQUE and <b>VALID</b> — a failed concurrent build leaves an INVALID
 *       index (PostgreSQL "Building Indexes Concurrently"), so validity is the
 *       proof the build completed.</li>
 *   <li><b>R2 behavior — the review's measured finding, verbatim:</b> the
 *       release carried by a different booking (the PENDING sibling of the
 *       holder) is a no-op; the holder's own release frees the window. This is
 *       the double-booking back door the finding describes: A and B on one
 *       window, A confirmed, B cancelled used to free A's slot.</li>
 *   <li><b>R3 behavior:</b> a second LIVE row for the same window is rejected
 *       by the partial unique index (23505), a soft-deleted row of the same
 *       window is tolerated (live-rows-only shape), and the daily generator
 *       skips a BOOKED window instead of inserting an open duplicate.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AvailabilitySlotOwnershipSchemaIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AvailabilityService availabilityService;

    // ---------- schema guards (V72 / V73) ----------

    @Test
    void v72_ownershipColumnExistsOnTableAndEnversMirror() {
        Integer onTable = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = 'availability_slots'"
                        + " AND column_name = 'held_by_booking_id'",
                Integer.class);
        Integer onMirror = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = 'availability_slots_aud'"
                        + " AND column_name = 'held_by_booking_id'",
                Integer.class);
        assertThat(onTable).as("V72 adds held_by_booking_id to availability_slots").isEqualTo(1);
        assertThat(onMirror).as("V72 mirrors the column into the Envers audit table").isEqualTo(1);
    }

    @Test
    void v73_liveWindowIndexExistsUniqueAndValid() {
        // indisvalid is the concurrent-build completion proof: a failed
        // CREATE INDEX CONCURRENTLY leaves an INVALID entry in the catalogs
        // and the documented recovery is drop-and-rebuild — this assertion
        // is the tripwire that would catch exactly that state.
        Boolean[] index = jdbcTemplate.queryForObject(
                "SELECT i.indisvalid, i.indisunique FROM pg_index i"
                        + " JOIN pg_class c ON i.indexrelid = c.oid"
                        + " WHERE c.relname = 'uq_availability_slots_live_window'",
                (rs, rowNum) -> new Boolean[] { rs.getBoolean(1), rs.getBoolean(2) });
        assertThat(index).as("V73 builds uq_availability_slots_live_window (valid + unique)").isNotNull();
        assertThat(index[0]).as("the concurrent build completed (not INVALID)").isTrue();
        assertThat(index[1]).as("the index enforces uniqueness").isTrue();
    }

    // ---------- R2: the review's measured scenario, verbatim ----------

    @Test
    void r2_nonOwnerCancelKeepsTheHeldSlot_ownerCancelFreesIt() {
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-08-03T09:00:00Z");
        Instant endsAt = Instant.parse("2026-08-03T10:00:00Z");
        UUID holderBooking = UUID.randomUUID();   // booking A — confirmed, holds the slot
        UUID siblingBooking = UUID.randomUUID();  // booking B — PENDING sibling

        insertSlot(providerId, startsAt, endsAt, false, null);

        // A confirms: the slot is booked IN A's name (the ownership claim).
        availabilityService.bookSlot(providerId, startsAt, endsAt, holderBooking);
        assertSlotState(providerId, startsAt, endsAt, true, holderBooking);

        // B cancels (the finding's exact scenario): the release carried by a
        // DIFFERENT booking must be a no-op — the window stays A's.
        availabilityService.releaseSlot(providerId, startsAt, endsAt, siblingBooking, null);
        assertSlotState(providerId, startsAt, endsAt, true, holderBooking);

        // The reverse direction of the same finding: A cancels after its
        // confirmation — its own hold is released, the window reopens.
        availabilityService.releaseSlot(providerId, startsAt, endsAt, holderBooking, null);
        assertSlotState(providerId, startsAt, endsAt, false, null);
    }

    @Test
    void r2_ownerCancelWithASurvivingActiveClaimantTransfersInsteadOfReopening() {
        // The review round on the wave's rebased head, verbatim: a legacy
        // window can carry older CONFIRMED/COMPLETED claimants alongside the
        // reconciled owner (V73 assigns the newest active booking). The
        // owner's cancellation must TRANSFER the hold to the surviving
        // claimant — not reopen the window for a new claim while the survivor
        // still expects it (the double-booking the round flagged).
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-08-04T09:00:00Z");
        Instant endsAt = Instant.parse("2026-08-04T10:00:00Z");
        UUID legacyClaimant = UUID.randomUUID(); // booking A — older CONFIRMED claimant
        UUID reconciledOwner = UUID.randomUUID(); // booking B — newest active, owns the window

        insertSlot(providerId, startsAt, endsAt, true, reconciledOwner);

        // B (the owner) cancels; the caller carries A as the surviving active
        // claimant — the window stays booked, now under A's name.
        availabilityService.releaseSlot(providerId, startsAt, endsAt, reconciledOwner, legacyClaimant);
        assertSlotState(providerId, startsAt, endsAt, true, legacyClaimant);

        // A (the transferred owner, the last active claimant) cancels with no
        // survivor — its own hold is released, the window reopens.
        availabilityService.releaseSlot(providerId, startsAt, endsAt, legacyClaimant, null);
        assertSlotState(providerId, startsAt, endsAt, false, null);
    }

    // ---------- R3: the one-live-row invariant ----------

    @Test
    void r3_indexRejectsSecondLiveWindow_toleratesSoftDeletedRow() {
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-08-04T09:00:00Z");
        Instant endsAt = Instant.parse("2026-08-04T10:00:00Z");

        insertSlot(providerId, startsAt, endsAt, false, null);

        // The concurrent racer the application check cannot see: a second LIVE
        // row for the same window lands on 23505 (Spring: DuplicateKeyException).
        assertThatThrownBy(() -> insertSlot(providerId, startsAt, endsAt, false, null))
                .isInstanceOf(DuplicateKeyException.class);

        // The live-rows-only shape: a soft-deleted row of the same window never
        // blocks the window (uq_conversations_direct_pair / uq_content_reports
        // precedent) — a re-opened window and its dead history coexist.
        insertSoftDeletedSlot(providerId, startsAt, endsAt);
        Integer liveRows = liveSlotCount(providerId, startsAt, endsAt);
        assertThat(liveRows).as("one live row per window; dead rows ride alongside").isEqualTo(1);
    }

    @Test
    void r3_generationSkipsABookedWindow_noOpenDuplicate() {
        UUID providerId = UUID.randomUUID();
        // A Monday whose 09:00-17:00 UTC window the rule below will regenerate.
        LocalDate monday = LocalDate.of(2026, 8, 10);
        Instant startsAt = monday.atTime(LocalTime.of(9, 0)).toInstant(ZoneOffset.UTC);
        Instant endsAt = monday.atTime(LocalTime.of(17, 0)).toInstant(ZoneOffset.UTC);

        // The rule that daily generation reads.
        jdbcTemplate.update(
                "INSERT INTO provider_availability_rules (id, provider_id, day_of_week, start_time, end_time,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), providerId, monday.getDayOfWeek().name(),
                java.sql.Time.valueOf(LocalTime.of(9, 0)), java.sql.Time.valueOf(LocalTime.of(17, 0)));

        // The window is HELD (booked by a confirmed booking) — the exact state
        // the old booked-blind probe could not see.
        UUID holderBooking = UUID.randomUUID();
        insertSlot(providerId, startsAt, endsAt, true, holderBooking);

        availabilityService.onDayHasPassed(org.springframework.modulith.moments.DayHasPassed.of(monday));

        // The R3 fix: generation skipped the BOOKED window — no open duplicate
        // was inserted next to the held row (the old code produced exactly one).
        Integer liveRows = liveSlotCount(providerId, startsAt, endsAt);
        assertThat(liveRows)
                .as("a booked slot IS the window — generation must not duplicate it")
                .isEqualTo(1);
    }

    // ---------- seeding / probing helpers ----------

    private void insertSlot(UUID providerId, Instant startsAt, Instant endsAt, boolean booked, UUID holder) {
        jdbcTemplate.update(
                "INSERT INTO availability_slots (id, provider_id, starts_at, ends_at, booked, held_by_booking_id,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), providerId, Timestamp.from(startsAt), Timestamp.from(endsAt), booked, holder);
    }

    private void insertSoftDeletedSlot(UUID providerId, Instant startsAt, Instant endsAt) {
        jdbcTemplate.update(
                "INSERT INTO availability_slots (id, provider_id, starts_at, ends_at, booked, is_deleted,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, false, true, now(), now())",
                UUID.randomUUID(), providerId, Timestamp.from(startsAt), Timestamp.from(endsAt));
    }

    private void assertSlotState(UUID providerId, Instant startsAt, Instant endsAt,
                                 boolean expectedBooked, UUID expectedHolder) {
        Boolean bookedRow = jdbcTemplate.queryForObject(
                "SELECT booked FROM availability_slots"
                        + " WHERE provider_id = ? AND starts_at = ? AND ends_at = ? AND is_deleted = false",
                Boolean.class, providerId, Timestamp.from(startsAt), Timestamp.from(endsAt));
        UUID holderRow = jdbcTemplate.queryForObject(
                "SELECT held_by_booking_id FROM availability_slots"
                        + " WHERE provider_id = ? AND starts_at = ? AND ends_at = ? AND is_deleted = false",
                UUID.class, providerId, Timestamp.from(startsAt), Timestamp.from(endsAt));
        assertThat(bookedRow).as("booked flag").isEqualTo(expectedBooked);
        assertThat(holderRow).as("held_by_booking_id").isEqualTo(expectedHolder);
    }

    private Integer liveSlotCount(UUID providerId, Instant startsAt, Instant endsAt) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM availability_slots"
                        + " WHERE provider_id = ? AND starts_at = ? AND ends_at = ? AND is_deleted = false",
                Integer.class, providerId, Timestamp.from(startsAt), Timestamp.from(endsAt));
    }
}
