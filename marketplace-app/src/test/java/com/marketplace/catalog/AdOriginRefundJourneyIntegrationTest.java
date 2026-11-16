package com.marketplace.catalog;

import com.marketplace.ledger.LedgerService;
import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentIntentRepository;
import com.marketplace.payments.PaymentRepository;
import com.marketplace.payments.PaymentsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * W7 (the unified plan §4 — the per-origin acceptance suites) — the AD
 * origin's own money laws, measured on the REAL chain (real PostgreSQL +
 * real Redis + the real Flyway schema + the real event machinery): the
 * billing chain's intent settles WITHOUT commission (the matrix's own
 * measured «قيدان مستقلان على حافتين» — AD_DEBIT at billing time, the
 * raw-amount PAYMENT_CREDIT at settlement time, no COMMISSION_DEBIT),
 * and the collected bill refunds in FULL through the ONE refund contract
 * (the intent REFUNDED, the ledger's REFUND_DEBIT mirroring the credit
 * under its nameUUID key) — plus the unpaid branch's honest design: the
 * payer's own generic cancel aborts the collectible attempt where it
 * stands, the campaign stays the window's own truth (G2's documented
 * design — no domain listener consumes payment events for AD), and the
 * billing-side AD_DEBIT entry is a fact the payment's cancel never
 * rewrites.
 *
 * <p>Boot pattern follows {@code AdBillingDeterminismIntegrationTest}
 * verbatim (the same seeds, the same executor, the same await shapes);
 * the money loop drives the REAL payments engine through its service
 * surfaces with the security context the method gates read (the
 * LoanDisputeJourney pattern verbatim).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AdOriginRefundJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the house precedent.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    /** The standard booking seam — the ads chain never touches it (the determinism suite's own proof). */
    @MockitoBean
    com.marketplace.shared.api.BookingParticipantProvider bookingParticipantProvider;

    @MockitoBean
    com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    @Autowired
    private AdBillingBatchExecutor billingExecutor;

    @Autowired
    private AdCampaignRepository campaignRepository;

    @Autowired
    private AdBillingChargeRepository chargeRepository;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentsService paymentsService;

    @Autowired
    private JdbcTemplate jdbc;

    private static final long CLICK_PRICE = 100L;
    private static final long IMPRESSION_PRICE = 5L;

    /**
     * The AD origin's settlement law (the matrix's own measured row):
     * the credit posts the RAW amount with NO commission — the payer of
     * an AD-origin intent IS the provider, and the platform's cut rode
     * the billing-time AD_DEBIT already, never a second debit at
     * settlement. The full refund then mirrors exactly that credit
     * through the ONE refund contract.
     */
    @Test
    void theAdOriginSettlesWithoutCommissionAndRefundsThroughTheOneContract() {
        // Budget 30000; traffic computes 2000×5 + 100×100 = 20000 (the
        // determinism suite's own arithmetic — the capped-at-budget
        // branch has its own unit guard).
        AdCampaign campaign = seedCampaignWithTraffic(30000L, 2000L, 100L, 3);

        billingExecutor.settleOneCampaign(campaign.getId(), LocalDate.now(ZoneOffset.UTC));

        var charges = chargeRepository.findByCampaignIdOrderByWindowStartDescIdDesc(campaign.getId());
        assertThat(charges).hasSize(1);
        var charge = charges.get(0);
        assertThat(charge.getAmountCents()).isEqualTo(20000L);

        // The intent — the ad-debit deterministic key, origin AD, payer
        // the provider (the payments listener's independent transaction
        // awaited on its own).
        PaymentIntent intent = awaitIntent("ad-debit-" + campaign.getId() + "-" + charge.getWindowStart());
        assertThat(intent.getOrigin()).isEqualTo("AD");
        assertThat(intent.getConsumerId()).isEqualTo(campaign.getProviderId());
        assertThat(intent.getAmountCents()).isEqualTo(20000L);

        // (1) The provider drives the money loop: process (PROCESSING +
        // the Payment row), then the administrative capture (SUCCEEDED).
        asRole("ROLE_CONSUMER", campaign.getProviderId());
        paymentsService.processIntent(intent.getId(),
                SecurityContextHolder.getContext().getAuthentication());
        asRole("ROLE_ADMIN", null);
        paymentsService.confirmIntent(intent.getId(), "j23-ad-capture-" + intent.getId());
        awaitIntentStatus(intent.getId(), "SUCCEEDED");

        // (2) The settlement's own law: the RAW-amount credit, NO
        // commission — the AD origin's matrix row measured on the ledger
        // (the billing-time AD_DEBIT was the platform's cut; settlement
        // never takes a second one).
        awaitLedgerCount(intent.getId(), "PAYMENT_CREDIT", 1);
        assertThat(jdbc.queryForObject(
                "SELECT amount_cents FROM ledger_entries WHERE source_id = ? AND entry_type = 'PAYMENT_CREDIT'",
                Long.class, intent.getId())).isEqualTo(20000L);
        UUID commissionSource = UUID.nameUUIDFromBytes(
                ("commission-" + intent.getId().toString()).getBytes());
        assertThat(jdbcTemplateCount(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ?", commissionSource))
                .as("the AD settlement posts NO commission debit (the matrix's own law)").isZero();

        // (3) The full refund through the ONE contract: the payment's
        // refund edge drives the intent to REFUNDED and the ledger's
        // REFUND_DEBIT mirrors the credit under its nameUUID key.
        UUID paymentId = jdbc.queryForObject(
                "SELECT id FROM payments WHERE payment_intent_id = ?", UUID.class, intent.getId());
        paymentsService.refundPayment(paymentId);
        awaitIntentStatus(intent.getId(), "REFUNDED");
        UUID refundSource = UUID.nameUUIDFromBytes(
                ("refund-" + intent.getId().toString()).getBytes());
        awaitLedgerCount(refundSource, "REFUND_DEBIT", 1);
        assertThat(jdbc.queryForObject(
                "SELECT amount_cents FROM ledger_entries WHERE source_id = ? AND entry_type = 'REFUND_DEBIT'",
                Long.class, refundSource))
                .as("the refund mirrors the collected bill in full").isEqualTo(20000L);

        // The billing-side facts are the window's own truth — the refund
        // never rewrites the charge or the campaign's consumed.
        assertThat(chargeRepository
                .findByCampaignIdOrderByWindowStartDescIdDesc(campaign.getId())).hasSize(1);
        campaignRepository.findById(campaign.getId()).ifPresent(c -> {
            assertThat(c.getConsumedCents()).isEqualTo(20000L);
        });
    }

    /**
     * The unpaid branch's honest design (the matrix's G2-documented
     * shape): the payer's own generic cancel aborts the collectible
     * attempt where it stands (the intent CANCELLED, no Payment row, no
     * settlement credit), the campaign stays the window's own truth (no
     * domain listener consumes payment events for AD — the pause is the
     * campaigns module's own lever, not a money event), and the
     * billing-side AD_DEBIT entry stays a fact.
     */
    @Test
    void theUnpaidAdIntentCancelsAndTheCampaignStaysTheWindowsTruth() {
        AdCampaign campaign = seedCampaignWithTraffic(30000L, 2000L, 100L, 3);

        billingExecutor.settleOneCampaign(campaign.getId(), LocalDate.now(ZoneOffset.UTC));
        var charge = chargeRepository.findByCampaignIdOrderByWindowStartDescIdDesc(campaign.getId()).get(0);
        PaymentIntent intent = awaitIntent("ad-debit-" + campaign.getId() + "-" + charge.getWindowStart());

        // The provider cancels their own unpaid collectible attempt.
        asRole("ROLE_CONSUMER", campaign.getProviderId());
        paymentsService.cancelIntent(intent.getId(),
                SecurityContextHolder.getContext().getAuthentication());
        awaitIntentStatus(intent.getId(), "CANCELLED");

        // No money ever moved: no Payment row, no settlement credit.
        assertThat(jdbcTemplateCount(
                "SELECT count(*) FROM payments WHERE payment_intent_id = ?", intent.getId())).isZero();
        assertThat(jdbcTemplateCount(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ?", intent.getId())).isZero();

        // The campaign is the window's own truth: ACTIVE, consumed as
        // charged (G2's documented design — the money's failure is not a
        // campaign fact; the pause is the module's own future-window
        // lever, never a payment-event reaction).
        campaignRepository.findById(campaign.getId()).ifPresent(c -> {
            assertThat(c.getStatus()).isEqualTo(AdCampaignStatus.ACTIVE);
            assertThat(c.getConsumedCents()).isEqualTo(20000L);
        });

        // The billing-side AD_DEBIT entry is a fact the cancel never
        // rewrites (the platform's cut rode the billing edge, not the
        // collection).
        UUID sourceKey = LedgerService.adDebitSourceKey(campaign.getId(), charge.getWindowStart());
        assertThat(jdbcTemplateCount(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ? AND entry_type = 'AD_DEBIT'",
                sourceKey)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // The seeds and awaits (the determinism suite's own shapes verbatim)
    // ------------------------------------------------------------------

    private AdCampaign seedCampaignWithTraffic(long budgetCents, long impressions, long clicks, int daysBack) {
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, providerId, "w7-ad-provider-" + providerId + "@example.com",
                "w7-ad-provider-" + providerId + "@example.com", "W7 Ad Provider");
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category,
                    price_cents, currency, status)
                VALUES (?, ?, 'w7 promoted listing', 'x', 'CLEANING', 25000, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, listingId, providerId);

        AdCampaign campaign = campaignRepository.save(AdCampaign.start(
                providerId, listingId, budgetCents, CLICK_PRICE, IMPRESSION_PRICE, "SAR",
                Instant.now().minus(Duration.ofDays(daysBack)), null));

        LocalDate firstBillable = LocalDate.now(ZoneOffset.UTC).minusDays(daysBack).plusDays(1);
        LocalDate lastBillable = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        long billableDays = java.time.temporal.ChronoUnit.DAYS.between(firstBillable, lastBillable) + 1;
        long perDayImpressions = impressions / billableDays;
        long perDayClicks = clicks / billableDays;
        for (LocalDate day = firstBillable; !day.isAfter(lastBillable); day = day.plusDays(1)) {
            jdbc.update("""
                    INSERT INTO listing_views_daily (id, listing_id, view_date, view_count)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (listing_id, view_date) DO UPDATE SET view_count = excluded.view_count
                    """, UUID.randomUUID(), listingId, day, perDayImpressions);
            jdbc.update("""
                    INSERT INTO ad_clicks_daily (id, campaign_id, click_date, click_count)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (campaign_id, click_date) DO UPDATE SET click_count = excluded.click_count
                    """, UUID.randomUUID(), campaign.getId(), day, perDayClicks);
        }
        jdbc.update("UPDATE listing_views_daily SET view_count = view_count + ? WHERE listing_id = ? AND view_date = ?",
                impressions - perDayImpressions * billableDays, listingId, firstBillable);
        jdbc.update("UPDATE ad_clicks_daily SET click_count = click_count + ? WHERE campaign_id = ? AND click_date = ?",
                clicks - perDayClicks * billableDays, campaign.getId(), firstBillable);
        return campaign;
    }

    /**
     * The security context the method gates read (the LoanDisputeJourney
     * pattern verbatim): the role the surface demands, the caller the
     * ownership gates resolve through the stubbed provider (null for the
     * admin surfaces — they read no caller).
     */
    private void asRole(String role, UUID callerId) {
        Authentication token = new TestingAuthenticationToken("w7-ad-journey", "n/a", role);
        SecurityContextHolder.getContext().setAuthentication(token);
        when(currentUserProvider.getCurrentUserId(any()))
                .thenReturn(callerId != null ? callerId : UUID.randomUUID());
    }

    private PaymentIntent awaitIntent(String idempotencyKey) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            var found = paymentIntentRepository.findByIdempotencyKey(idempotencyKey);
            if (found.isPresent()) {
                return found.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Payment intent " + idempotencyKey + " never appeared");
    }

    /** The bounded poll for the intent's own status (the AFTER_COMMIT hop). */
    private void awaitIntentStatus(UUID intentId, String expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM payment_intents WHERE id = ?", String.class, intentId);
            if (expected.equals(status)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("payment intent " + intentId + " did not reach " + expected
                + " within 10s");
    }

    /** The bounded poll for a typed ledger row (the listener's AFTER_COMMIT hop). */
    private void awaitLedgerCount(UUID sourceId, String entryType, int expected) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM ledger_entries WHERE source_id = ? AND entry_type = ?",
                    Integer.class, sourceId, entryType);
            if (rows != null && rows >= expected) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("ledger rows of type " + entryType + " for " + sourceId
                + " never reached " + expected + " within 10s");
    }

    private int jdbcTemplateCount(String sql, UUID arg) {
        Integer count = jdbc.queryForObject(sql, Integer.class, arg);
        return count == null ? 0 : count;
    }
}
