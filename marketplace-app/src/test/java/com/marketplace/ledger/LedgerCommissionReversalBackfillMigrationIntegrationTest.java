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

    /** The runtime twin's own seam (CodeRabbit round 8's post-stop pass). */
    @Autowired
    private com.marketplace.ledger.CommissionReversalReconciliation reconciliation;

    @Test
    void theRuntimeSweep_convergesTheDeployOverlapStragglers() {
        // The deploy-overlap scenario V298's snapshot cannot see: the OLD
        // deployment's refund listener wrote the PRE-A2 shape AFTER the new
        // deployment's Flyway pass ran — a deserving refund the one-time
        // migration missed forever (its publication completed under the
        // pre-fix code, so the registry never replays it). The scheduled
        // sweep's own seam converges it after the old version is gone.
        UUID consumerId = UUID.randomUUID();
        UUID stragglerProvider = UUID.randomUUID();
        UUID stragglerIntent = UUID.randomUUID();

        seedProviderWorld(stragglerProvider, consumerId, stragglerIntent);
        insertEntry(stragglerProvider, stragglerIntent, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(stragglerProvider, commissionSource(stragglerIntent), "COMMISSION_DEBIT", COMMISSION_CENTS);
        insertEntry(stragglerProvider, refundSource(stragglerIntent), "REFUND_DEBIT", PRICE_CENTS);
        seedBalance(stragglerProvider, PRICE_CENTS - COMMISSION_CENTS - PRICE_CENTS); // minus the commission

        // The post-stop pass: ONE convergence run answers the one straggler.
        int converged = reconciliation.reconcileNow();
        assertThat(converged)
                .as("the sweep created exactly the one missing reversal row")
                .isEqualTo(1);
        assertThat(countEntries(reversalSource(stragglerIntent), "COMMISSION_REVERSAL_CREDIT"))
                .as("the straggler gains its mirror row — the posted commission's own amount")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount_cents FROM ledger_entries WHERE source_id = ?",
                Long.class, reversalSource(stragglerIntent)))
                .isEqualTo(COMMISSION_CENTS);
        assertThat(balanceOf(stragglerProvider))
                .as("the straggler's balance returns to the honest zero")
                .isEqualTo(0L);

        // A converged world answers zero and moves nothing — the sweep's
        // own idle signal (every later scheduled run is a no-op).
        long entriesBefore = totalEntries();
        assertThat(reconciliation.reconcileNow())
                .as("the second sweep finds nothing to converge")
                .isZero();
        assertThat(totalEntries()).isEqualTo(entriesBefore);
        assertThat(balanceOf(stragglerProvider)).isZero();
    }

    @Test
    void v298_backfillsOnlyTheDeservingPreFixRefunds_andIsIdempotent() throws Exception {
        // Three INDEPENDENT providers — one per case: the balance is keyed
        // (provider, currency), so every case's money lives on its own
        // provider's row and no seed can overwrite another case's state.
        UUID consumerId = UUID.randomUUID();
        UUID deservingProvider = UUID.randomUUID();   // the A2 defect's own residue
        UUID fixedProvider = UUID.randomUUID();        // the post-fix shape: mirror already present
        UUID zeroProvider = UUID.randomUUID();         // the zero-commission refund: nothing to reverse
        UUID sumProvider = UUID.randomUUID();           // CodeRabbit round 7: TWO deserving refunds, one balance key
        UUID deservingIntent = UUID.randomUUID();
        UUID fixedIntent = UUID.randomUUID();
        UUID zeroIntent = UUID.randomUUID();
        UUID sumIntentOne = UUID.randomUUID();
        UUID sumIntentTwo = UUID.randomUUID();

        seedProviderWorld(deservingProvider, consumerId, deservingIntent);
        seedProviderWorld(fixedProvider, consumerId, fixedIntent);
        seedProviderWorld(zeroProvider, consumerId, zeroIntent);
        seedProviderWorld(sumProvider, consumerId, sumIntentOne);
        seedProviderWorld(sumProvider, consumerId, sumIntentTwo);

        // ---- The deserving case: settlement pair + refund debit, NO mirror ----
        insertEntry(deservingProvider, deservingIntent, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(deservingProvider, commissionSource(deservingIntent), "COMMISSION_DEBIT", COMMISSION_CENTS);
        insertEntry(deservingProvider, refundSource(deservingIntent), "REFUND_DEBIT", PRICE_CENTS);
        seedBalance(deservingProvider, PRICE_CENTS - COMMISSION_CENTS - PRICE_CENTS); // minus the commission

        // ---- The already-fixed case: the mirror rode the refund ----
        insertEntry(fixedProvider, fixedIntent, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(fixedProvider, commissionSource(fixedIntent), "COMMISSION_DEBIT", COMMISSION_CENTS);
        insertEntry(fixedProvider, refundSource(fixedIntent), "REFUND_DEBIT", PRICE_CENTS);
        insertEntry(fixedProvider, reversalSource(fixedIntent), "COMMISSION_REVERSAL_CREDIT", COMMISSION_CENTS);
        seedBalance(fixedProvider, 0L);

        // ---- The zero-commission case: no COMMISSION_DEBIT ever existed ----
        insertEntry(zeroProvider, zeroIntent, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(zeroProvider, refundSource(zeroIntent), "REFUND_DEBIT", PRICE_CENTS);
        seedBalance(zeroProvider, 0L);

        // ---- CodeRabbit round 7: TWO deserving refunds on the SAME provider
        //      and currency — the balance sits at minus BOTH commissions and
        //      must return to zero in ONE move by the SUM of the two reversals
        //      (one balance key, one upsert, never two writes racing the row). ----
        insertEntry(sumProvider, sumIntentOne, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(sumProvider, commissionSource(sumIntentOne), "COMMISSION_DEBIT", COMMISSION_CENTS);
        insertEntry(sumProvider, refundSource(sumIntentOne), "REFUND_DEBIT", PRICE_CENTS);
        insertEntry(sumProvider, sumIntentTwo, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(sumProvider, commissionSource(sumIntentTwo), "COMMISSION_DEBIT", COMMISSION_CENTS);
        insertEntry(sumProvider, refundSource(sumIntentTwo), "REFUND_DEBIT", PRICE_CENTS);
        seedBalance(sumProvider, 2 * (PRICE_CENTS - COMMISSION_CENTS - PRICE_CENTS)); // minus both commissions

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
        assertThat(balanceOf(deservingProvider))
                .as("the provider's balance returns from minus-the-commission to zero")
                .isEqualTo(0L);

        // ---- The non-deserving cases stay untouched ----
        assertThat(countEntries(reversalSource(fixedIntent), "COMMISSION_REVERSAL_CREDIT"))
                .as("the already-mirrored refund is not re-mirrored (count stays one)")
                .isEqualTo(1);
        assertThat(balanceOf(fixedProvider))
                .as("the already-fixed provider's balance is untouched")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ?",
                Long.class, reversalSource(zeroIntent)))
                .as("the zero-commission refund gains no reversal — nothing was ever over-debited")
                .isZero();
        assertThat(balanceOf(zeroProvider))
                .as("the zero-commission provider's balance is untouched")
                .isZero();

        // ---- The round-7 aggregation outcome: two deserving refunds, one
        //      balance key — both reversal rows exist and the balance moved
        //      by their SUM in the single upsert ----
        assertThat(countEntries(reversalSource(sumIntentOne), "COMMISSION_REVERSAL_CREDIT"))
                .as("the first deserving refund of the shared provider gains its reversal row")
                .isEqualTo(1);
        assertThat(countEntries(reversalSource(sumIntentTwo), "COMMISSION_REVERSAL_CREDIT"))
                .as("the second deserving refund of the shared provider gains its reversal row")
                .isEqualTo(1);
        assertThat(balanceOf(sumProvider))
                .as("the shared provider's balance returns from minus-both-commissions to zero "
                        + "— the two reversals moved it by their SUM in one aggregated upsert")
                .isEqualTo(0L);

        // ---- Idempotency: the second execution moves nothing ----
        long entriesBefore = totalEntries();
        long deservingBalanceBefore = balanceOf(deservingProvider);
        executeV298();
        assertThat(totalEntries()).as("the re-run inserts nothing").isEqualTo(entriesBefore);
        assertThat(balanceOf(deservingProvider))
                .as("the re-run moves no balance")
                .isEqualTo(deservingBalanceBefore);
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
     * with ON CONFLICT DO NOTHING, idempotent per test. Each case's provider
     * carries its OWN listing and booking (the booking row's provider FK is
     * one provider), so the three cases never share a money row.
     */
    private void seedProviderWorld(UUID providerId, UUID consumerId, UUID intentId) {
        UUID listingId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
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
