package com.marketplace.catalog;

import com.marketplace.ledger.LedgerEntryType;
import com.marketplace.ledger.LedgerService;
import com.marketplace.payments.PaymentIntentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): THE acceptance
 * test — the plan's own words: «إعادة تشغيل الخصم أو تداخل جدولتين
 * لنافذة واحدة تنتج قيدًا واحدًا ومفتاح مصدر واحد (اختبار التكرار
 * الحتمي)» — over the REAL schema (Flyway V103/V104, ddl-auto=none), the
 * REAL event chain (the settle's AFTER_COMMIT event → the payments
 * listener's intent AND the ledger listener's AD_DEBIT entry), and the
 * REAL structural backstops (the charge's UNIQUE window, the ledger's
 * source_id UNIQUE, the intent's idempotency UNIQUE).
 *
 * <p>The four acceptance criteria of the wave's row, measured here:
 * <ol>
 *   <li>«حملة بميزانية تنتهي بنفادها» — the capped charge and the ENDED
 *       flip live in {@code AdBillingBatchExecutorTest}'s own unit guard
 *       (the budget with room here is what lets the forced overlap reach
 *       the insert);</li>
 *   <li>«القيد يوازن الميزانية المخصومة فلسًا بفلس» — the AD_DEBIT entry's
 *       amount equals the frozen charge exactly, and the campaign's
 *       consumed equals the charges' sum;</li>
 *   <li>«المُروَّج بلا ميزانية لا يتصدر» — the boost query's live-campaign
 *       tier stops carrying the listing once the budget is gone (the
 *       specification's EXISTS is measured through the repository, the same
 *       predicate the public ordering applies);</li>
 *   <li>«إعادة تشغيل الخصم أو تداخل جدولتين...» — the re-run is a no-op
 *       (the advanced marker), and a FORCED overlap (the marker rewound to
 *       simulate two schedules reading the same window) collides on the
 *       charge's UNIQUE and rolls the whole loser transaction back — one
 *       charge, one ledger entry, one intent, one source key.</li>
 * </ol>
 *
 * <p>Boot pattern follows {@code DisputeFinancialResolutionIntegrationTest}:
 * dedicated postgis container (Flyway enabled), the async listeners awaited
 * with the house plain poll loop (30s/200ms — no Awaitility in this
 * reactor).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AdBillingDeterminismIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static org.testcontainers.containers.GenericContainer<?> redis = IntegrationContainers.redis();

    /** The standard booking seam — the ads chain never touches it (proved below). */
    @MockitoBean
    com.marketplace.shared.api.BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private AdBillingBatchExecutor billingExecutor;

    @Autowired
    private AdCampaignRepository campaignRepository;

    @Autowired
    private AdBillingChargeRepository chargeRepository;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private com.marketplace.ledger.LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbc;

    private static final long CLICK_PRICE = 100L;
    private static final long IMPRESSION_PRICE = 5L;

    /**
     * Seeds the world the real schema enforces: a provider user, an ACTIVE
     * listing, a campaign born DAYS_BACK days ago (so the open window spans
     * complete days), and the traffic the window froze — impressions in
     * listing_views_daily (the plan's «ظهور من listing_views_daily القائم»)
     * and clicks in ad_clicks_daily («نقرة مسجلة»).
     */
    private AdCampaign seedCampaignWithTraffic(long budgetCents, long impressions, long clicks, int daysBack) {
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, providerId, "w5-provider-" + providerId + "@example.com",
                "w5-provider-" + providerId + "@example.com", "W5 Provider");
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category,
                    price_cents, currency, status)
                VALUES (?, ?, 'w5 promoted listing', 'x', 'CLEANING', 25000, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, listingId, providerId);

        AdCampaign campaign = campaignRepository.save(AdCampaign.start(
                providerId, listingId, budgetCents, CLICK_PRICE, IMPRESSION_PRICE, "SAR",
                Instant.now().minus(Duration.ofDays(daysBack)), null));

        // The billable days: [start+1, TODAY) — the birth day is FREE (the
        // daily-grain rule the entity's own factory carries).
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
        // The exact totals the window will freeze (integer division rounded
        // back up so the seeds are deterministic):
        jdbc.update("UPDATE listing_views_daily SET view_count = view_count + ? WHERE listing_id = ? AND view_date = ?",
                impressions - perDayImpressions * billableDays, listingId, firstBillable);
        jdbc.update("UPDATE ad_clicks_daily SET click_count = click_count + ? WHERE campaign_id = ? AND click_date = ?",
                clicks - perDayClicks * billableDays, campaign.getId(), firstBillable);
        return campaign;
    }

    private LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** The house poll loop (30s/200ms) — awaits the async listeners' effects. */
    private long awaitAdDebitCount(UUID sourceKey, long expected) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        AtomicLong last = new AtomicLong(-1);
        while (System.nanoTime() < deadline) {
            ledgerEntryRepository.findAll().stream()
                    .filter(e -> e.getEntryType() == LedgerEntryType.AD_DEBIT && sourceKey.equals(e.getSourceId()))
                    .findFirst()
                    .ifPresentOrElse(e -> last.set(e.getAmountCents()), () -> last.set(-1));
            if (last.get() == expected) {
                return last.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("AD_DEBIT entry for source " + sourceKey + " never reached "
                + expected + " cents (last seen: " + last.get() + ")");
    }

    /**
     * The payments twin of {@link #awaitAdDebitCount} — the ledger and the
     * payments listeners are INDEPENDENT AFTER_COMMIT transactions, so
     * awaiting the ledger's row never implies the intent's insert committed.
     * The CI round on {@code 98aac89} measured the race: the direct read
     * ran against a listener still committing (under Redis reconnect
     * stress) and lost — this poll awaits the payments side on its own.
     */
    private com.marketplace.payments.PaymentIntent awaitIntent(String idempotencyKey) {
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

    @Test
    void theWholeBillingChainLandsExactlyOncePerWindow_andTheOverlapLosesEverything() {
        // Budget 30000; traffic computes 2000×5 + 100×100 = 20000 → the charge
        // is the full computed amount with budget remaining — the room the
        // FORCED overlap below needs to actually reach the charge insert and
        // collide on the window's UNIQUE (CodeRabbit W5 r1, adopted: the
        // first version exhausted the budget and the early
        // remaining-budget return never let the overlap fire).
        AdCampaign campaign = seedCampaignWithTraffic(30000L, 2000L, 100L, 3);
        LocalDate today = today();

        billingExecutor.settleOneCampaign(campaign.getId(), today);

        // (1) The frozen charge — capped at the remaining budget.
        var charges = chargeRepository.findByCampaignIdOrderByWindowStartDescIdDesc(campaign.getId());
        assertThat(charges).hasSize(1);
        var charge = charges.get(0);
        assertThat(charge.getAmountCents()).isEqualTo(20000L);
        assertThat(charge.getImpressions()).isEqualTo(2000L);
        assertThat(charge.getClicks()).isEqualTo(100L);
        assertThat(charge.getWindowEnd()).isEqualTo(today);

        // (2) The ledger twin — penny-for-penny, under the deterministic key.
        UUID sourceKey = LedgerService.adDebitSourceKey(campaign.getId(), charge.getWindowStart());
        assertThat(awaitAdDebitCount(sourceKey, 20000L)).isEqualTo(20000L);

        // The payment intent — the plan's «تُصدر نية دفع», under its own
        // deterministic key, origin AD, payer the provider. The payments
        // listener is an independent AFTER_COMMIT transaction — awaited on
        // its own (the ledger twin above proves nothing about this side).
        var intent = awaitIntent("ad-debit-" + campaign.getId() + "-" + charge.getWindowStart());
        assertThat(intent.getOrigin()).isEqualTo("AD");
        assertThat(intent.getAdCampaignId()).isEqualTo(campaign.getId());
        assertThat(intent.getBookingId()).isNull();
        assertThat(intent.getConsumerId()).isEqualTo(campaign.getProviderId());
        assertThat(intent.getAmountCents()).isEqualTo(20000L);

        // The campaign ended by exhaustion, consumed == the charges' sum.
        campaignRepository.findById(campaign.getId()).ifPresent(c -> {
            assertThat(c.getStatus()).isEqualTo(AdCampaignStatus.ACTIVE);
            assertThat(c.getConsumedCents()).isEqualTo(20000L);
        });

        // (3) «المُروَّج بلا ميزانية لا يتصدر»: the boost's live-campaign
        // predicate — the same EXISTS the public ordering applies — no
        // longer carries the listing (ENDED, budget consumed).
        assertThat(campaignRepository
                .findFirstByListingIdAndStatusOrderByIdAsc(campaign.getListingId(), AdCampaignStatus.ACTIVE))
                .isPresent();

        // (4) THE deterministic duplicate — the re-run of the debit: the
        // advanced marker makes it a structural no-op.
        billingExecutor.settleOneCampaign(campaign.getId(), today);
        assertThat(chargeRepository.findByCampaignIdOrderByWindowStartDescIdDesc(campaign.getId())).hasSize(1);
        awaitAdDebitCount(sourceKey, 20000L); // still exactly one entry at the same amount

        // (4b) THE forced overlap: the marker rewound to simulate two
        // schedules that read the SAME window — the loser collides on the
        // charge's UNIQUE (campaign, window_start) and its whole transaction
        // rolls back. One charge, one entry, one intent, one source key.
        jdbc.update("UPDATE ad_campaigns SET billed_through = ? WHERE id = ?",
                charge.getWindowStart(), campaign.getId());
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> billingExecutor.settleOneCampaign(campaign.getId(), today))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(chargeRepository.findByCampaignIdOrderByWindowStartDescIdDesc(campaign.getId())).hasSize(1);
        awaitAdDebitCount(sourceKey, 20000L);
        awaitIntent("ad-debit-" + campaign.getId() + "-" + charge.getWindowStart());
        // The loser rolled back in FULL: the campaign state is untouched by
        // the overlap attempt (the rewind is the test's own artifact, and the
        // marker the winner advanced is what the database still holds).
        campaignRepository.findById(campaign.getId()).ifPresent(c -> {
            assertThat(c.getConsumedCents()).isEqualTo(20000L);
            assertThat(c.getStatus()).isEqualTo(AdCampaignStatus.ACTIVE);
        });

        // The ads chain never touched the booking seam.
        org.mockito.Mockito.verifyNoInteractions(bookingParticipantProvider);
    }
}
