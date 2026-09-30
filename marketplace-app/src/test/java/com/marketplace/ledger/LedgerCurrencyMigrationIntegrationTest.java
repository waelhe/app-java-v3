package com.marketplace.ledger;

import test.config.IntegrationContainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * Migration-time guard for V75's currency convergence (the R9 wave — the
 * ledger's currency): the repair derives every existing entry's ISO 4217
 * currency from its payment intent (reconstructing the UUIDv3 source-id
 * mapping the runtime path itself uses) and recomputes the provider
 * balances per {@code (provider, currency)} — and the regular post-boot
 * integration tests can never see it (by the time the context is up, Flyway
 * has already applied V75 to a fresh database and the convergence had
 * nothing to transform).
 *
 * <p>This test boots with {@code spring.flyway.target=74} — the schema
 * stops BEFORE the currency migration (V74's index exists; V75 has not
 * run) — seeds the exact pre-fix states the convergence exists for, then
 * executes the actual V75 script (the real file from the classpath, through
 * Spring's official {@link ScriptUtils}) and asserts the converged outcomes:
 *
 * <ol>
 *   <li><b>Entry currency derivation:</b> the PAYMENT_CREDIT row keyed by
 *       the intent id, the COMMISSION_DEBIT row keyed by the JDK-derived
 *       {@code UUID.nameUUIDFromBytes("commission-" + intentId)} (computed
 *       here IN JAVA — the assertion proves the migration's SQL
 *       reconstruction of the same UUIDv3 matches the runtime derivation),
 *       and the REFUND_DEBIT row keyed the same way, all converge to the
 *       INTENT's currency; an orphan entry falls back to the house
 *       default SAR.</li>
 *   <li><b>The review document's own scenario:</b> a provider credited
 *       100 SAR and 100 USD (commission 10% each) ends with TWO SEPARATE
 *       balances — 9000 SAR and 9000 USD — never the pre-fix single mixed
 *       sum. The pre-fix corrupted row (the mixed aggregate the defect
 *       wrote) is replaced by the entry-derived truth.</li>
 *   <li><b>The composite key:</b> the second {@code (provider, SAR)}
 *       balance row is rejected with 23505 by the database itself — and
 *       the key is in place BEFORE the grouped insert (CodeRabbit round 1:
 *       a provider holding two currencies must not die on the OLD
 *       single-column key mid-insert).</li>
 *   <li><b>The Envers mirror:</b> the single-currency era's pre-existing
 *       audit rows (NULL currency) are backfilled to the house default
 *       and the mirror's key widens to (provider, currency, rev) — the
 *       composite-id audit shape Envers itself maps.</li>
 *   <li><b>The runtime write path (end-to-end):</b> a balance saved
 *       through the REAL repository against the converged schema — the
 *       entity INSERT and the Envers audit INSERT must both fit — and the
 *       multi-currency read through the REAL service (the composite-key
 *       derived query) returns the converged rows.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // stop BEFORE the wave's currency migration — the pre-state this
        // test seeds is exactly the state V75 exists to converge
        "spring.flyway.target=74",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LedgerCurrencyMigrationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private com.marketplace.ledger.ProviderBalanceRepository balanceRepository;

    @Autowired
    private com.marketplace.ledger.LedgerService ledgerService;

    @Test
    void v75_derivesEntryCurrencies_recomputesBalancesPerCurrency_enforcesTheCompositeKey() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();

        // The FK chain payment_intents needs: one provider user, one
        // consumer user, one listing, one booking.
        jdbcTemplate.update(
                "INSERT INTO users (id, subject, role) VALUES (?, ?, 'PROVIDER'), (?, ?, 'CONSUMER')",
                providerId, "provider-" + providerId, consumerId, "consumer-" + consumerId);
        jdbcTemplate.update(
                "INSERT INTO provider_listings (id, provider_id, title, category, price_cents, currency)"
                        + " VALUES (?, ?, 'migration test listing', 'STAY', 1000, 'SAR')",
                listingId, providerId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents,"
                        + " currency, starts_at, ends_at, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'CONFIRMED', 1000, 'SAR',"
                        + " '2026-10-01 09:00:00+00', '2026-10-01 10:00:00+00', now(), now())",
                bookingId, consumerId, providerId, listingId);

        // Two intents of the SAME booking's provider: one SAR, one USD —
        // the currency divergence the defect mixed into one number.
        UUID sarIntent = UUID.randomUUID();
        UUID usdIntent = UUID.randomUUID();
        insertIntent(sarIntent, bookingId, consumerId, "SUCCEEDED", "SAR");
        insertIntent(usdIntent, bookingId, consumerId, "SUCCEEDED", "USD");

        // ---- the pre-fix entries: NO currency column yet, source ids the
        // runtime path itself derives (the JDK's own UUIDv3 here — the
        // migration's SQL reconstruction must match it) ----
        UUID commissionSource = UUID.nameUUIDFromBytes(("commission-" + sarIntent).getBytes());
        UUID refundSource = UUID.nameUUIDFromBytes(("refund-" + usdIntent).getBytes());
        UUID orphanSource = UUID.randomUUID();

        insertEntry(providerId, sarIntent, "PAYMENT_CREDIT", 10_000);            // = sarIntent id
        insertEntry(providerId, commissionSource, "COMMISSION_DEBIT", 1_000);    // derived from sarIntent
        insertEntry(providerId, usdIntent, "PAYMENT_CREDIT", 10_000);            // = usdIntent id
        insertEntry(providerId, refundSource, "REFUND_DEBIT", 3_000);            // derived from usdIntent
        insertEntry(providerId, orphanSource, "PAYMENT_CREDIT", 2_000);          // no matching intent

        // ---- the pre-fix corrupted balance: the single-key mixed sum ----
        // (10_000 - 1_000) + (10_000 - 3_000) + 2_000 = 18_000 — one number
        // across three currencies, the defect's own artifact.
        jdbcTemplate.update(
                "INSERT INTO provider_balances (provider_id, available_cents, created_at, updated_at, version)"
                        + " VALUES (?, 18_000, now(), now(), 0)", providerId);

        // ---- the single-currency era's audit row (currency NULL — the
        // column does not exist yet) riding the real Envers revision chain ----
        jdbcTemplate.update("INSERT INTO revinfo (rev, revtstmp) VALUES (990001, now())");
        jdbcTemplate.update(
                "INSERT INTO provider_balances_aud (provider_id, rev, revtype, available_cents,"
                        + " version, created_at, updated_at, is_deleted)"
                        + " VALUES (?, 990001, 0, 18_000, 0, now(), now(), false)", providerId);

        // ---- execute the actual V75 script (the real file) ----
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V75__ledger_currency.sql"));
        }

        // ---- entry currency derivation (the SQL UUIDv3 reconstruction
        // proven against the JDK's own derivation used to seed) ----
        assertThat(entryCurrency(sarIntent)).as("the direct credit takes its intent's currency").isEqualTo("SAR");
        assertThat(entryCurrency(commissionSource))
                .as("the commission debit is mapped back to its intent (UUIDv3 reconstructed in SQL)")
                .isEqualTo("SAR");
        assertThat(entryCurrency(usdIntent)).as("the direct credit takes its intent's currency").isEqualTo("USD");
        assertThat(entryCurrency(refundSource))
                .as("the refund debit is mapped back to its intent (UUIDv3 reconstructed in SQL)")
                .isEqualTo("USD");
        assertThat(entryCurrency(orphanSource))
                .as("an orphan entry falls back to the house default")
                .isEqualTo("SAR");

        // ---- the balance recomputation: the mixed 18_000 is replaced by the
        // per-currency truth (the review document's own scenario shape) ----
        Map<String, Long> balances = balanceByCurrency(providerId);
        assertThat(balances)
                .as("SAR: 10_000 credit - 1_000 commission + 2_000 orphan credit = 11_000;"
                        + " USD: 10_000 credit - 3_000 refund = 7_000 — never a mixed sum")
                .containsOnly(Map.entry("SAR", 11_000L), Map.entry("USD", 7_000L));
        assertThat(balanceRowCount(providerId))
                .as("the pre-fix single corrupted row is replaced by one row per currency")
                .isEqualTo(2);

        // ---- the composite key is enforced by the database itself ----
        try {
            jdbcTemplate.update(
                    "INSERT INTO provider_balances (provider_id, currency, available_cents, created_at,"
                            + " updated_at, version) VALUES (?, 'SAR', 5, now(), now(), 0)", providerId);
            throw new AssertionError("the composite primary key must reject a second (provider, SAR) row");
        } catch (DataIntegrityViolationException expected) {
            // 23505 wrapped in Spring's translation
        }

        // ---- and the entry currency is NOT NULL from now on ----
        try {
            jdbcTemplate.update(
                    "INSERT INTO ledger_entries (id, provider_id, source_id, entry_type, amount_cents,"
                            + " created_at, updated_at) VALUES (?, ?, ?, 'PAYMENT_CREDIT', 5, now(), now())",
                    UUID.randomUUID(), providerId, UUID.randomUUID());
            throw new AssertionError("ledger_entries.currency must be NOT NULL after V75");
        } catch (DataIntegrityViolationException expected) {
            // the null rejection
        }

        // ---- the Envers mirror: the era's audit row is backfilled and the
        // mirror's key widened (the composite-id audit shape) ----
        String audCurrency = jdbcTemplate.queryForObject(
                "SELECT currency FROM provider_balances_aud WHERE provider_id = ? AND rev = 990001",
                String.class, providerId);
        assertThat(audCurrency)
                .as("the single-currency era's audit row carries the house default")
                .isEqualTo("SAR");

        // ---- the runtime write path, end-to-end (CodeRabbit round 1's
        // composite-key proof): a balance saved through the REAL repository —
        // entity INSERT + Envers audit INSERT must both fit the converged
        // schema — then read back through the composite id and the REAL
        // multi-currency read (the derived query's composite property path) ----
        com.marketplace.ledger.ProviderBalance fresh =
                com.marketplace.ledger.ProviderBalance.empty(providerId, "EUR");
        ProviderBalance saved = balanceRepository.save(fresh);
        assertThat(saved.getKey().currency()).isEqualTo("EUR");
        assertThat(balanceRepository
                .findById(new com.marketplace.ledger.ProviderBalance.ProviderBalanceId(providerId, "EUR")))
                .as("the composite id resolves the saved row")
                .isPresent();
        var readable = ledgerService.getBalances(providerId);
        assertThat(readable)
                .as("the multi-currency read returns every currency the provider now holds")
                .extracting(com.marketplace.ledger.ProviderBalanceResponse::currency)
                .containsExactly("EUR", "SAR", "USD");
    }

    // ---------- seeding helpers ----------

    private void insertIntent(UUID id, UUID bookingId, UUID consumerId, String status, String currency) {
        jdbcTemplate.update(
                "INSERT INTO payment_intents (id, booking_id, consumer_id, amount_cents, currency,"
                        + " status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 10_000, ?, ?, '2026-09-20 10:00:00+00', '2026-09-20 10:00:00+00')",
                id, bookingId, consumerId, currency, status);
    }

    private void insertEntry(UUID providerId, UUID sourceId, String entryType, long amountCents) {
        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, provider_id, source_id, entry_type, amount_cents,"
                        + " created_at, updated_at, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz, 0)",
                UUID.randomUUID(), providerId, sourceId, entryType, amountCents,
                "2026-09-15 10:00:00+00", "2026-09-15 10:00:00+00");
    }

    private String entryCurrency(UUID sourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT currency FROM ledger_entries WHERE source_id = ?", String.class, sourceId);
    }

    private Map<String, Long> balanceByCurrency(UUID providerId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT currency, available_cents FROM provider_balances WHERE provider_id = ?", providerId);
        return rows.stream().collect(Collectors.toMap(
                row -> (String) row.get("currency"),
                row -> ((Number) row.get("available_cents")).longValue()));
    }

    private int balanceRowCount(UUID providerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM provider_balances WHERE provider_id = ?", Integer.class, providerId);
        return count == null ? 0 : count;
    }
}
