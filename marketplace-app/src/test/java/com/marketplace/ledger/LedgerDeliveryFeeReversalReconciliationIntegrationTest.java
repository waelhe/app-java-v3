package com.marketplace.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import test.config.IntegrationContainers;

/**
 * F-832-1 + CodeRabbit round 2: the delivery-fee-reversal sweep's own guard
 * — the fee twin of the commission-reversal reconciliation's backfill guard
 * ({@code LedgerCommissionReversalBackfillMigrationIntegrationTest}), on the
 * real PostgreSQL schema (the real migration chain: the widened closed set
 * from V301/V302 is what makes the sweep's insert legal).
 *
 * <p><b>The exposure this guard pins.</b> The fee mirror (the posted
 * DELIVERY_FEE_DEBIT's own amount reversed in the refund transaction) ships
 * with this branch — every refund the PREVIOUS deployment processes after
 * the new one's boots lands the refund leg WITHOUT the fee mirror, its
 * publication COMPLETE (the pre-fix code does not fail), so the registry
 * never replays it. The scheduled sweep is the convergence story: this test
 * drives its own seam ({@code reconcileNow()}) on exactly those pre-mirror
 * states and asserts the outcomes:</p>
 *
 * <ol>
 *   <li><b>The deserving straggler:</b> an intent settled (PAYMENT_CREDIT +
 *       DELIVERY_FEE_DEBIT) and fully refunded (REFUND_DEBIT) by the
 *       pre-mirror code — the balance sits at minus the fee. After the
 *       sweep: the DELIVERY_FEE_REVERSAL_CREDIT row exists with the posted
 *       fee's OWN amount, keyed by the runtime path's deterministic
 *       {@code delivery-fee-reversal-<intentId>} source id, the balance
 *       returns to its honest zero, and the balance row's optimistic version
 *       moved with the money (the round-9 stale-save interleave's demand,
 *       the commission sweep's own letter).</li>
 *   <li><b>Idempotency:</b> a second sweep inserts nothing and moves
 *       nothing — every deserving reversal row already exists.</li>
 *   <li><b>The non-deserving cases stay untouched:</b> a refunded zero-fee
 *       settlement (no DELIVERY_FEE_DEBIT row ever existed — the PICKUP
 *       no-op), an already-mirrored refund, and a settlement not yet
 *       refunded (no REFUND_DEBIT row) gain no rows and change no balances
 *       — the zero-separation and the eligibility cut, applied verbatim.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LedgerDeliveryFeeReversalReconciliationIntegrationTest {

    private static final long PRICE_CENTS = 17400L;
    private static final long FEE_CENTS = 4000L;

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The runtime twin's own seam (the round-2 review's convergence demand). */
    @Autowired
    private DeliveryFeeReversalReconciliation reconciliation;

    @Test
    void theRuntimeSweep_convergesTheDeployOverlapStragglers() {
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();

        seedProviderWorld(providerId, consumerId, intentId, "REFUNDED");
        insertEntry(providerId, intentId, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(providerId, feeSource(intentId), "DELIVERY_FEE_DEBIT", FEE_CENTS);
        insertEntry(providerId, refundSource(intentId), "REFUND_DEBIT", PRICE_CENTS);
        seedBalance(providerId, PRICE_CENTS - FEE_CENTS - PRICE_CENTS); // minus the fee

        long versionBefore = balanceVersionOf(providerId);

        // The post-stop pass: ONE convergence run answers the one straggler.
        int converged = reconciliation.reconcileNow();
        assertThat(converged)
                .as("the sweep created exactly the one missing fee-reversal row")
                .isEqualTo(1);
        assertThat(countEntries(feeReversalSource(intentId), "DELIVERY_FEE_REVERSAL_CREDIT"))
                .as("the straggler gains its mirror row — the posted fee's own amount")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount_cents FROM ledger_entries WHERE source_id = ?",
                Long.class, feeReversalSource(intentId)))
                .as("the sweep reverses the POSTED fee row's own amount — never a recomputation")
                .isEqualTo(FEE_CENTS);
        assertThat(balanceOf(providerId))
                .as("the straggler's balance returns from minus-the-fee to the honest zero")
                .isEqualTo(0L);
        assertThat(balanceVersionOf(providerId))
                .as("the sweep's raw UPSERT bumps the optimistic version exactly as every JPA save does "
                        + "(the commission sweep's round-9 letter, carried verbatim)")
                .isEqualTo(versionBefore + 1);

        // A converged world answers zero and moves nothing — the sweep's
        // own idle signal (every later scheduled run is a no-op).
        long entriesBefore = totalEntries();
        assertThat(reconciliation.reconcileNow())
                .as("the second sweep finds nothing to converge")
                .isZero();
        assertThat(totalEntries()).isEqualTo(entriesBefore);
        assertThat(balanceOf(providerId)).isZero();
    }

    @Test
    void theSweep_leavesTheNonDeservingStatesAlone() {
        UUID consumerId = UUID.randomUUID();

        // Case 1 — the zero-fee refund (the PICKUP no-op): settlement posted
        // its credit and the refund its debit, but NO fee row ever existed —
        // nothing was over-debited, nothing to reverse.
        UUID zeroFeeProvider = UUID.randomUUID();
        UUID zeroFeeIntent = UUID.randomUUID();
        seedProviderWorld(zeroFeeProvider, consumerId, zeroFeeIntent, "REFUNDED");
        insertEntry(zeroFeeProvider, zeroFeeIntent, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(zeroFeeProvider, refundSource(zeroFeeIntent), "REFUND_DEBIT", PRICE_CENTS);
        seedBalance(zeroFeeProvider, 0L);

        // Case 2 — the already-mirrored refund (the post-fix shape): the fee
        // mirror rode the refund transaction; the sweep must not re-mirror.
        UUID fixedProvider = UUID.randomUUID();
        UUID fixedIntent = UUID.randomUUID();
        seedProviderWorld(fixedProvider, consumerId, fixedIntent, "REFUNDED");
        insertEntry(fixedProvider, fixedIntent, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(fixedProvider, feeSource(fixedIntent), "DELIVERY_FEE_DEBIT", FEE_CENTS);
        insertEntry(fixedProvider, refundSource(fixedIntent), "REFUND_DEBIT", PRICE_CENTS);
        insertEntry(fixedProvider, feeReversalSource(fixedIntent), "DELIVERY_FEE_REVERSAL_CREDIT", FEE_CENTS);
        seedBalance(fixedProvider, 0L);

        // Case 3 — the not-yet-refunded settlement: the fee debit exists but
        // no REFUND_DEBIT — the eligibility cut (status REFUNDED + the
        // refund's own row) keeps the live settlement untouched.
        UUID liveProvider = UUID.randomUUID();
        UUID liveIntent = UUID.randomUUID();
        seedProviderWorld(liveProvider, consumerId, liveIntent, "COMPLETED");
        insertEntry(liveProvider, liveIntent, "PAYMENT_CREDIT", PRICE_CENTS);
        insertEntry(liveProvider, feeSource(liveIntent), "DELIVERY_FEE_DEBIT", FEE_CENTS);
        seedBalance(liveProvider, PRICE_CENTS - FEE_CENTS);

        // The sweep runs across all three cases at once.
        assertThat(reconciliation.reconcileNow())
                .as("no non-deserving state gains a row")
                .isZero();

        assertThat(countEntries(feeReversalSource(zeroFeeIntent), "DELIVERY_FEE_REVERSAL_CREDIT"))
                .as("the zero-fee refund gains no reversal — nothing was ever over-debited")
                .isZero();
        assertThat(balanceOf(zeroFeeProvider)).isZero();

        assertThat(countEntries(feeReversalSource(fixedIntent), "DELIVERY_FEE_REVERSAL_CREDIT"))
                .as("the already-mirrored refund is not re-mirrored (count stays one)")
                .isEqualTo(1);
        assertThat(balanceOf(fixedProvider)).isZero();

        assertThat(countEntries(feeReversalSource(liveIntent), "DELIVERY_FEE_REVERSAL_CREDIT"))
                .as("the live settlement's fee is not reversed before its refund exists")
                .isZero();
        assertThat(balanceOf(liveProvider))
                .as("the live settlement's balance is untouched")
                .isEqualTo(PRICE_CENTS - FEE_CENTS);
    }

    /** The runtime path's own derivation — the JDK UUIDv3 the SQL reconstructs. */
    private static UUID feeSource(UUID intentId) {
        return UUID.nameUUIDFromBytes(("delivery-fee-" + intentId).getBytes());
    }

    private static UUID refundSource(UUID intentId) {
        return UUID.nameUUIDFromBytes(("refund-" + intentId).getBytes());
    }

    private static UUID feeReversalSource(UUID intentId) {
        return UUID.nameUUIDFromBytes(("delivery-fee-reversal-" + intentId).getBytes());
    }

    /**
     * The FK chain payment_intents' booking origin enforces (users →
     * provider_listings → bookings) — the commission backfill test's seeding
     * convention verbatim, parametrized by the intent status so the live-
     * settlement case can ride the same chain.
     */
    private void seedProviderWorld(UUID providerId, UUID consumerId, UUID intentId, String intentStatus) {
        UUID listingId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, providerId, "f832-sweep-provider-" + providerId,
                "f832-sweep-provider-" + providerId + "@example.com",
                "F-832 Sweep Provider");
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """, consumerId, "f832-sweep-consumer-" + consumerId,
                "f832-sweep-consumer-" + consumerId + "@example.com",
                "F-832 Sweep Consumer");
        jdbcTemplate.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category,
                    price_cents, currency, status)
                VALUES (?, ?, 'f832 sweep listing', 'x', 'CLEANING', ?, 'SAR', 'ACTIVE')
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
                VALUES (?, ?, ?, 'BOOKING', ?, 'SAR', ?, 0)
                """, intentId, bookingId, consumerId, PRICE_CENTS, intentStatus);
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

    private long balanceVersionOf(UUID providerId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM provider_balances WHERE provider_id = ? AND currency = 'SAR'",
                Long.class, providerId);
        return version == null ? Long.MIN_VALUE : version;
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
}
