package com.marketplace.provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * L25 (feature-expansion roadmap §5) — the provider's three aggregates over
 * the REAL modules and a REAL Flyway schema: the stats service resolves the
 * slot aggregates through the real {@code AvailabilityLookupAdapter}, the
 * signed ledger sum through the real {@code LedgerStatsAdapter} and the
 * completed count through the real {@code BookingStatsAdapter} — no mocks
 * between the ports and PostgreSQL. The ownership guard runs the REAL
 * AuthHelper against the REAL provider profile (only the principal
 * resolution — CurrentUserProvider — is the mocked seam, the L20 precedent).
 *
 * <p>Id-space note (the AuthHelper A1 contract): the cross-module
 * {@code provider_id} columns (availability_slots, ledger_entries,
 * bookings) carry {@code users.id} — the stats aggregate by the owner's
 * USER id, and the profile row exists to satisfy the ownership resolution
 * and the FK parents.
 *
 * <p>Boot pattern follows {@code CatalogSearchFullTextIntegrationTest}:
 * isolated {@code postgres:18-alpine} container via
 * {@code @ServiceConnection}, Flyway enabled, {@code ddl-auto=none}.
 *
 * <p>Acceptance criterion 1 — known dataset, the three numbers match a
 * manual calculation documented right here:
 * <pre>
 *   slots starting in [2026-09-01, 2026-10-01): 6 (Sep 1, 2, 3 + exactly-at-
 *   from + Sep 10 + Sep 20), booked among them: 2 (Sep 10, Sep 20)
 *     -> occupancy = 2/6 = 0.3333...
 *   ledger entries created in the window (created_at 2026-09-15):
 *   PAYMENT_CREDIT 10_000, COMMISSION_DEBIT 1_000, REFUND_DEBIT 3_000
 *     -> net = 10_000 - 1_000 - 3_000 = 6_000 cents
 *   COMPLETED bookings starting in the window: 2 (Sep 5, Sep 15); the
 *   PENDING one, the foreign provider's, the one starting exactly at `to`
 *   and the one before `from` do not count
 *     -> completed = 2
 * </pre>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProviderStatsIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private ProviderStatsService statsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // The stats path is guarded by the REAL @authHelper.ownsProvider; only
    // the principal resolution is the mocked seam (the L20 integration
    // precedent — the guard then resolves the REAL profile row).
    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    private static final Instant FROM = LocalDate.of(2026, 9, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant TO = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant MID_WINDOW = LocalDate.of(2026, 9, 15).atStartOfDay(ZoneOffset.UTC).toInstant();

    private static final UUID OWNER_USER_ID = UUID.randomUUID();
    private static final UUID OTHER_PROVIDER_USER_ID = UUID.randomUUID();
    private static final UUID CONSUMER_USER_ID = UUID.randomUUID();
    private static final UUID LISTING_ID = UUID.randomUUID();

    @BeforeEach
    void seedTheKnownDataset() {
        jdbcTemplate.update("DELETE FROM bookings");
        jdbcTemplate.update("DELETE FROM availability_slots");
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM provider_balances");
        jdbcTemplate.update("DELETE FROM provider_profiles WHERE user_id IN (?, ?)",
                OWNER_USER_ID, OTHER_PROVIDER_USER_ID);
        jdbcTemplate.update("DELETE FROM provider_listings WHERE id = ?", LISTING_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?)",
                OWNER_USER_ID, OTHER_PROVIDER_USER_ID, CONSUMER_USER_ID);

        for (UUID userId : java.util.List.of(OWNER_USER_ID, OTHER_PROVIDER_USER_ID, CONSUMER_USER_ID)) {
            jdbcTemplate.update(
                    """
                    INSERT INTO users (id, subject, email, display_name, role)
                    VALUES (?, ?, ?, ?, 'PROVIDER')
                    """,
                    userId, "l25-" + userId, "l25-" + userId + "@example.com", "L25 User " + userId);
        }

        // The owner's provider profile — the ownership guard resolves it
        // via findByUserId(OWNER_USER_ID) (profiles.id is its own space,
        // irrelevant to the cross-module aggregates — A1).
        jdbcTemplate.update(
                """
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at, version, is_deleted)
                VALUES (?, 'L25 Stats Provider', 'bio', 'VERIFIED', ?, now(), now(), 0, false)
                """,
                UUID.randomUUID(), OWNER_USER_ID);

        // The FK parent for every seeded booking (V3: listing_id references
        // provider_listings(id)).
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status, created_at, updated_at, version, is_deleted)
                VALUES (?, ?, 'L25 Seed Listing', 'seed', 'home', 1000, 'SAR', 'ACTIVE', now(), now(), 0, false)
                """,
                LISTING_ID, OWNER_USER_ID);

        // --- slots: 6 start inside [FROM, TO) — Sep 1, 2, 3 (free) + one
        // starting EXACTLY at FROM (free, the inclusive bound) + Sep 10 and
        // Sep 20 (booked). Outside: one before FROM, one starting exactly
        // at TO (the exclusive bound) — neither counts.
        for (int day : new int[]{1, 2, 3}) {
            slot(LocalDate.of(2026, 9, day).atStartOfDay(ZoneOffset.UTC).toInstant(), false);
        }
        slot(FROM, false);
        slot(LocalDate.of(2026, 9, 10).atStartOfDay(ZoneOffset.UTC).toInstant(), true);
        slot(LocalDate.of(2026, 9, 20).atStartOfDay(ZoneOffset.UTC).toInstant(), true);
        slot(FROM.minusSeconds(3600), false);
        slot(TO, true);

        // --- ledger: the three entries land at a FIXED in-window instant
        // (created_at is the window key — a fixed date keeps the test
        // deterministic forever); plus another provider's credit.
        ledgerEntry(OWNER_USER_ID, "PAYMENT_CREDIT", 10_000);
        ledgerEntry(OWNER_USER_ID, "COMMISSION_DEBIT", 1_000);
        ledgerEntry(OWNER_USER_ID, "REFUND_DEBIT", 3_000);
        ledgerEntry(OTHER_PROVIDER_USER_ID, "PAYMENT_CREDIT", 99_999);

        // --- bookings (all start inside the window unless stated):
        // counted: 2 COMPLETED (Sep 5, Sep 15).
        booking(OWNER_USER_ID, "COMPLETED", LocalDate.of(2026, 9, 5));
        booking(OWNER_USER_ID, "COMPLETED", LocalDate.of(2026, 9, 15));
        // not counted: PENDING inside the window.
        booking(OWNER_USER_ID, "PENDING", LocalDate.of(2026, 9, 6));
        // not counted: COMPLETED but another provider's.
        booking(OTHER_PROVIDER_USER_ID, "COMPLETED", LocalDate.of(2026, 9, 25));
        // not counted: COMPLETED starting exactly at `to` (exclusive end).
        booking(OWNER_USER_ID, "COMPLETED", null, TO);
        // not counted: COMPLETED starting before `from`.
        booking(OWNER_USER_ID, "COMPLETED", null, FROM.minusSeconds(3600));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM bookings");
        jdbcTemplate.update("DELETE FROM availability_slots");
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM provider_balances");
        jdbcTemplate.update("DELETE FROM provider_profiles WHERE user_id IN (?, ?)",
                OWNER_USER_ID, OTHER_PROVIDER_USER_ID);
        jdbcTemplate.update("DELETE FROM provider_listings WHERE id = ?", LISTING_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?)",
                OWNER_USER_ID, OTHER_PROVIDER_USER_ID, CONSUMER_USER_ID);
    }

    @Test
    @WithMockUser
    void threeAggregates_matchTheManualCalculation() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);

        ProviderStatsResponse stats = statsService.getStats(OWNER_USER_ID, new StatsWindow(FROM, TO));

        // The documented manual calculation (acceptance criterion 1):
        assertThat(stats.occupancyRate()).isCloseTo(2.0 / 6.0, offset(1e-9));
        assertThat(stats.netRevenueCents()).isEqualTo(6_000L);
        assertThat(stats.completedBookings()).isEqualTo(2L);
        assertThat(stats.from()).isEqualTo(FROM);
        assertThat(stats.to()).isEqualTo(TO);
    }

    @Test
    @WithMockUser
    void ownershipGate_blocksForeignCallersBeforeAnyAggregate() {
        // A different authenticated user does not own OWNER_USER_ID's
        // provider space — the REAL AuthHelper denies (findByUserId
        // resolves the owner's profile, whose userId is not the caller's).
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> statsService.getStats(OWNER_USER_ID, new StatsWindow(FROM, TO)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser
    void windowWithNoSlots_reportsZerosWithoutFailing() {
        // The empty-aggregate boundary (PR #257 review round): SUM over
        // zero rows is SQL NULL while COUNT is 0 — a window with no slots
        // (a new provider, or a future window) must return honest zeros,
        // never a null-boxing failure. A window in 2027 matches nothing.
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);

        ProviderStatsResponse stats = statsService.getStats(OWNER_USER_ID,
                new StatsWindow(LocalDate.of(2027, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                        LocalDate.of(2027, 7, 1).atStartOfDay(ZoneOffset.UTC).toInstant()));

        assertThat(stats.occupancyRate()).isZero();
        assertThat(stats.netRevenueCents()).isZero();
        assertThat(stats.completedBookings()).isZero();
    }

    private void slot(Instant startsAt, boolean booked) {
        jdbcTemplate.update(
                "INSERT INTO availability_slots (id, provider_id, starts_at, ends_at, booked, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                UUID.randomUUID(), OWNER_USER_ID, Timestamp.from(startsAt), Timestamp.from(startsAt.plusSeconds(3600)), booked);
    }

    private void ledgerEntry(UUID providerUserId, String entryType, long amountCents) {
        jdbcTemplate.update(
                """
                INSERT INTO ledger_entries (id, provider_id, source_id, entry_type, amount_cents, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), providerUserId, UUID.randomUUID(), entryType, amountCents,
                Timestamp.from(MID_WINDOW), Timestamp.from(MID_WINDOW));
    }

    private void booking(UUID providerUserId, String status, LocalDate day) {
        booking(providerUserId, status, day, day.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private void booking(UUID providerUserId, String status, LocalDate day, Instant explicitStart) {
        Instant startsAt = explicitStart != null ? explicitStart
                : day.atStartOfDay(ZoneOffset.UTC).toInstant();
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, starts_at, ends_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1000, 'SAR', ?, ?, now(), now())
                """,
                UUID.randomUUID(), CONSUMER_USER_ID, providerUserId, LISTING_ID, status,
                Timestamp.from(startsAt), Timestamp.from(startsAt.plusSeconds(3600)));
    }
}
