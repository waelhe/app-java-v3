package com.marketplace.ledger;

import test.config.IntegrationContainers;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Migration-time guard for V298's reconciliation (CodeRabbit round 6 on the
 * full review — the inline finding at V296 lines 34-38): the backfill is the
 * highest-stakes SQL of the A2 wave — it converges PRE-FIX refund data at
 * deploy time, and the regular post-boot integration tests can never see it
 * (by the time the context is up, Flyway has already applied V298 to a fresh
 * database and the one-time repair had nothing to transform).
 *
 * <p>This test boots with {@code spring.flyway.target=297} — the schema stops
 * AFTER the closed-set widening (V296/V297: COMMISSION_REVERSAL_CREDIT is a
 * legal entry_type) but BEFORE the backfill — seeds the exact pre-fix states
 * the reconciliation exists for, then executes the actual V298 script (the
 * real file from the classpath, through Spring's official {@link ScriptUtils}
 * on an autocommit connection — a single atomic data-modifying CTE, so the
 * statement itself is the transaction) and asserts the converged outcomes:
 *
 * <ol>
 *   <li><b>The deserving case (the A2 defect's own residue):</b> an intent
 *       settled and fully refunded by the PRE-FIX code — the ledger holds
 *       the settlement pair (PAYMENT_CREDIT + COMMISSION_DEBIT) and the
 *       refund's REFUND_DEBIT but NOT the pair's mirror member, and the
 *       balance sits at minus the commission. After the repair: the
 *       COMMISSION_REVERSAL_CREDIT row exists with the posted commission's
 *       OWN amount and currency, keyed by the runtime path's deterministic
 *       {@code commission-reversal-<intentId>} source id, and the balance
 *       returns to its honest zero.</li>
 *   <li><b>Idempotency (the V57 re-runnable letter):</b> a second execution
 *       of the same script inserts nothing and moves nothing — every
 *       deserving reversal row already exists.</li>
 *   <li><b>The non-deserving cases stay untouched:</b> an intent whose refund
 *       ALREADY carries its mirror (the post-fix shape) and a refunded
 *       zero-commission settlement (no COMMISSION_DEBIT row ever existed)
 *       gain no rows and change no balances — the round-3 zero-separation,
 *       applied to the historical data too.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // stop AFTER the closed-set widening, BEFORE the backfill — the
        // pre-state this test seeds is exactly the state V298 exists to
        // converge, with the widened CHECK already legal for the insert
        "spring.flyway.target=297",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LedgerCommissionReversalBackfillMigrationIntegrationTest {

    private static final long PRICE_CENTS = 5000L;
    private static final long COMMISSION_CENTS = 500L;

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void v298_backfillsOnlyTheDeservingPreFixRefunds_andIsIdempotent() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID deservingIntent = UUID.randomUUID();   // the A2 defect's own residue
        UUID fixedIntent = UUID.randomUUID();      // the post-fix shape: mirror already present
        UUID zeroCommissionIntent = UUID.randomUUID(); // the zero-commission refund: nothing to reverse

        seedFkParents(providerId, consumerId, listingId, bookingId);
        seedBookingIntent(deservingIntent, bookingId, consumerId);
        seedBookingIntent(fixedIntent, bookingId, consumerId);
        seedBookingIntent(zeroCommissionIntent, bookingId, consumerId);

        // ---- The deserving case: settlement pair + refund debit, NO mirror ----
        insertEntry(providerId, deservingIntent, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(providerId, commissionSource(deservingIntent), "COMMISSION_DEBIT", COMMISSION_CENTS);
        insertEntry(providerId, refundSource(deservingIntent), "REFUND_DEBIT", PRICE_CENTS);
        seedBalance(providerId, PRICE_CENTS - COMMISSION_CENTS - PRICE_CENTS); // minus the commission

        // ---- The already-fixed case: the mirror rode the refund ----
        insertEntry(providerId, fixedIntent, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(providerId, commissionSource(fixedIntent), "COMMISSION_DEBIT", COMMISSION_CENTS);
        insertEntry(providerId, refundSource(fixedIntent), "REFUND_DEBIT", PRICE_CENTS);
        insertEntry(providerId, reversalSource(fixedIntent), "COMMISSION_REVERSAL_CREDIT", COMMISSION_CENTS);
        seedBalance(providerId, 0L);

        // ---- The zero-commission case: no COMMISSION_DEBIT ever existed ----
        insertEntry(providerId, zeroCommissionIntent, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(providerId, refundSource(zeroCommissionIntent), "REFUND_DEBIT", PRICE_CENTS);
        seedBalance(providerId, 0L);

        // ---- execute the actual V298 script (the real file from the
        // classpath; the migration is one atomic data-modifying CTE, so the
        // autocommit connection matches its single-statement shape) ----
        executeV298();

        // ---- The deserving outcome: the mirror row, the posted amount,
        //      the deterministic key, the honest zero balance ----
        assertThat(countEntries(reversalSource(deservingIntent), "COMMISSION_REVERSAL_CREDIT"))
                .as("the deserving pre-fix refund gains its reversal row")
                .isEqualTo(1);
        Long reversalAmount = jdbcTemplate.queryForObject(
                "SELECT amount_cents FROM ledger_entries WHERE source_id = ?",
                Long.class, reversalSource(deservingIntent));
        assertThat(reversalAmount)
                .as("the backfill reverses the POSTED commission row's own amount")
                .isEqualTo(COMMISSION_CENTS);
        assertThat(balanceOf(providerId))
                .as("the provider's balance returns from minus-the-commission to zero")
                .isEqualTo(0L);

        // ---- The non-deserving cases stay untouched ----
        assertThat(countEntries(reversalSource(fixedIntent), "COMMISSION_REVERSAL_CREDIT"))
                .as("the already-mirrored refund is not re-mirrored (count stays one)")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ?",
                Long.class, reversalSource(zeroCommissionIntent)))
                .as("the zero-commission refund gains no reversal — nothing was ever over-debited")
                .isZero();

        // ---- Idempotency: the second execution moves nothing ----
        long entriesBefore = totalEntries();
        long balanceBefore = balanceOf(providerId);
        executeV298();
        assertThat(totalEntries()).as("the re-run inserts nothing").isEqualTo(entriesBefore);
        assertThat(balanceOf(providerId)).as("the re-run moves no balance").isEqualTo(balanceBefore);
    }

    /** The runtime path's own derivation — the JDK UUIDv3 the SQL reconstructs. */
    private static UUID commissionSource(UUID intentId) {
        return UUID.nameUUIDFromBytes(("commission-" + intentId).getBytes());
    }

    private static UUID refundSource(UUID intentId) {
        return UUID.nameUUIDFromBytes(("refund-" + intentId).getBytes());
    }

    private static UUID reversalSource(UUID intentId) {
        return UUID.nameUUIDFromBytes(("commission-reversal-" + intentId).getBytes());
    }

    /**
     * The FK chain payment_intents' booking origin enforces (users →
     * provider_listings → bookings) — the {@code
     * DisputeFinancialResolutionIntegrationTest} seeding convention: raw SQL
     * with ON CONFLICT DO NOTHING, idempotent per test.
     */
    private void seedFkParents(UUID providerId, UUID consumerId, UUID listingId, UUID bookingId) {
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, providerId, "a2-bf-provider-" + providerId,
                "a2-bf-provider-" + providerId + "@example.com",
                "A2 Backfill Provider");
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """, consumerId, "a2-bf-consumer-" + consumerId,
                "a2-bf-consumer-" + consumerId + "@example.com",
                "A2 Backfill Consumer");
        jdbcTemplate.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category,
                    price_cents, currency, status)
                VALUES (?, ?, 'a2 backfill listing', 'x', 'CLEANING', ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, listingId, providerId, PRICE_CENTS);
        jdbcTemplate.update("""
                INSERT INTO bookings (id, listing_id, consumer_id, provider_id, status, price_cents, currency)
                VALUES (?, ?, ?, ?, 'CONFIRMED', ?, 'SAR')
                ON CONFLICT (id) DO NOTHING
                """, bookingId, listingId, consumerId, providerId, PRICE_CENTS);
    }

    private void seedBookingIntent(UUID intentId, UUID bookingId, UUID consumerId) {
        jdbcTemplate.update("""
                INSERT INTO payment_intents (id, booking_id, consumer_id, origin, amount_cents,
                    currency, status, refunded_amount_cents)
                VALUES (?, ?, ?, 'BOOKING', 25000, 'SAR', 'REFUNDED', 0)
                """, intentId, bookingId, consumerId);
    }

    private void insertEntry(UUID providerId, UUID sourceId, String entryType, long amountCents) {
        jdbcTemplate.update("""
                INSERT INTO ledger_entries (id, provider_id, source_id, entry_type, amount_cents,
                    currency, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'SAR', now(), now())
                """, UUID.randomUUID(), providerId, sourceId, entryType, amountCents);
    }

    private void seedBalance(UUID providerId, long availableCents) {
        jdbcTemplate.update("""
                INSERT INTO provider_balances (provider_id, currency, available_cents, created_at, updated_at)
                VALUES (?, 'SAR', ?, now(), now())
                ON CONFLICT (provider_id, currency)
                DO UPDATE SET available_cents = EXCLUDED.available_cents
                """, providerId, availableCents);
    }

    private long balanceOf(UUID providerId) {
        Long cents = jdbcTemplate.queryForObject(
                "SELECT available_cents FROM provider_balances WHERE provider_id = ? AND currency = 'SAR'",
                Long.class, providerId);
        return cents == null ? Long.MIN_VALUE : cents;
    }

    private long countEntries(UUID sourceId, String entryType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ? AND entry_type = ?",
                Long.class, sourceId, entryType);
        return count == null ? 0 : count;
    }

    private long totalEntries() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries", Long.class);
        return count == null ? 0 : count;
    }

    private void executeV298() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource(
                            "db/migration/V298__ledger_entries_commission_reversal_backfill.sql"));
        }
    }
}
