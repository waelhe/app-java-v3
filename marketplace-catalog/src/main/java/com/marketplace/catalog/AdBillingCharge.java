package com.marketplace.catalog;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the immutable
 * charge record — the plan's «سجل شحن غير قابل للتعديل لكل نافذة يجمد
 * الاستهلاك المفوتر». One row per (campaign, window): the window is
 * {@code [windowStart, windowEnd)} in UTC days, the impressions and
 * clicks are the FROZEN consumption the charge billed (impressions from
 * the existing {@code listing_views_daily}, clicks from
 * {@code ad_clicks_daily}), and {@code amountCents} is what hit the
 * budget — capped at the remaining budget the moment the charge was cut.
 *
 * <p><b>Immutability is structural, not advisory:</b> the class exposes
 * NO mutator — the only write is the insert. No service in the codebase
 * ever issues an UPDATE against the table; the Envers trail therefore
 * shows ADD-only revisions, the strongest form of the plan's freeze
 * («يجمد الاستهلاك المفوتر»).</p>
 *
 * <p><b>The deterministic identity:</b> {@code (campaignId, windowStart)}
 * is UNIQUE (V103) — a re-run of the debit or two overlapping schedules
 * for one window can produce at most ONE row («إعادة تشغيل الخصم أو
 * تداخل جدولتين لنافذة واحدة تنتج قيدًا واحدًا ومفتاح مصدر واحد»);
 * the losing transaction rolls back entirely, and the ledger's
 * {@code AD_DEBIT:{campaignId}:{windowStart}} source key keeps the
 * ledger twin exactly as unique as the charge itself.</p>
 */
@Entity
@Table(name = "ad_billing_charges")
@Audited
public class AdBillingCharge extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** The window's inclusive first UTC day — the deterministic key's second component. */
    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    /** The window's exclusive end UTC day. */
    @Column(name = "window_end", nullable = false)
    private LocalDate windowEnd;

    /** The frozen impression count the charge billed (from listing_views_daily). */
    @Column(name = "impressions", nullable = false)
    private long impressions;

    /** The frozen click count the charge billed (from ad_clicks_daily). */
    @Column(name = "clicks", nullable = false)
    private long clicks;

    /** The frozen charge amount — what hit the budget (capped at the remaining budget). */
    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    protected AdBillingCharge() {
        // JPA
    }

    private AdBillingCharge(UUID id, UUID campaignId, LocalDate windowStart, LocalDate windowEnd,
                            long impressions, long clicks, long amountCents, String currency) {
        this.id = id;
        this.campaignId = campaignId;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.impressions = impressions;
        this.clicks = clicks;
        this.amountCents = amountCents;
        this.currency = currency;
    }

    /**
     * The freeze itself — the ONLY factory. The caller (the billing
     * batch executor) has already capped the amount at the remaining
     * budget and computed the frozen counts; the insert is the whole
     * transaction's point, atomically paired with the campaign's
     * {@code consume()} + {@code markBilledThrough()} moves.
     */
    static AdBillingCharge freeze(UUID campaignId, LocalDate windowStart, LocalDate windowEnd,
                                  long impressions, long clicks, long amountCents, String currency) {
        return new AdBillingCharge(UUID.randomUUID(), campaignId, windowStart, windowEnd,
                impressions, clicks, amountCents, currency);
    }

    UUID getCampaignId() { return campaignId; }
    LocalDate getWindowStart() { return windowStart; }
    LocalDate getWindowEnd() { return windowEnd; }
    long getImpressions() { return impressions; }
    long getClicks() { return clicks; }
    long getAmountCents() { return amountCents; }
    String getCurrency() { return currency; }

    @Override
    public UUID getId() { return id; }
}
