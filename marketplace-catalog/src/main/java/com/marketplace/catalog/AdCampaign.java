package com.marketplace.catalog;

import com.marketplace.shared.api.Currencies;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the paid
 * promotion — the plan's «حملات (ad_campaigns: ميزانية، تسعير نقرة/ظهور،
 * مدة)». One campaign promotes ONE listing with ONE budget: the
 * {@code uq_ad_campaigns_one_active_per_listing} partial unique index
 * (V103) holds the single-live-campaign law the service's friendly 409
 * states first.
 *
 * <p><b>The money model (the plan's own words):</b> {@code budgetCents}
 * is the hard ceiling — consumption caps at it and the campaign ENDS the
 * moment a charge reaches it («حملة بميزانية تنتهي بنفادها»); both event
 * prices bill together (a zero price honestly makes that event free — the
 * plan prices «نقرة/ظهور» as a pair); {@code consumedCents} is the frozen
 * billed running total, denormalized in the SAME transaction as each
 * immutable charge insert so it is always exactly
 * {@code SUM(ad_billing_charges.amount_cents)} (the V85 stored-pair
 * precedent); {@code currency} is the money's own denomination — V82's
 * multi-currency ledger carries it through to the balance.</p>
 *
 * <p><b>The window identity (the plan's «مؤشر billed_through»):</b>
 * {@code billedThrough} is the FIRST unsettled UTC date — everything
 * strictly before it is frozen inside immutable charge rows. The daily
 * billing run settles {@code [billedThrough, horizon)} as ONE window,
 * advances the marker atomically with the charge insert, and the pause
 * gap skips billing because {@code resume()} moves the marker past the
 * paused days (the provider never pays for days the campaign was dark).</p>
 */
@Entity
@Table(name = "ad_campaigns")
@Audited
public class AdCampaign extends BaseEntity {

    @Id
    private UUID id;

    /** The advertiser — the listing's owner and the ad bill's payer. */
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /** The promoted listing (the FK is this module's own table — the V5 booking_id precedent). */
    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    /** The hard ceiling — consumption caps at it, the campaign ends when it is reached. */
    @Column(name = "budget_cents", nullable = false)
    private long budgetCents;

    /** The per-click price (cents) — bills together with the impression price. */
    @Column(name = "click_price_cents", nullable = false)
    private long clickPriceCents;

    /** The per-impression price (cents) — bills together with the click price. */
    @Column(name = "impression_price_cents", nullable = false)
    private long impressionPriceCents;

    /**
     * The frozen billed running total — always exactly
     * {@code SUM(ad_billing_charges.amount_cents)}: the charge insert and
     * this column move in the SAME transaction (see the class javadoc).
     */
    @Column(name = "consumed_cents", nullable = false)
    private long consumedCents;

    /** The money's own ISO 4217 denomination (carried to the charge, the intent and the ledger). */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private AdCampaignStatus status = AdCampaignStatus.ACTIVE;

    /** The campaign's birth instant (the accrual's first billable date is its UTC date). */
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** The duration's end — null means "until the budget runs out". */
    @Column(name = "ends_at")
    private Instant endsAt;

    /**
     * The window identity: the FIRST unsettled UTC date. Everything
     * strictly before it is frozen inside immutable charge rows; the
     * billing run settles forward from it and never backward.
     */
    @Column(name = "billed_through", nullable = false)
    private LocalDate billedThrough;

    protected AdCampaign() {
        // JPA
    }

    private AdCampaign(UUID id, UUID providerId, UUID listingId, long budgetCents,
                       long clickPriceCents, long impressionPriceCents, String currency,
                       Instant startsAt, Instant endsAt, LocalDate billedThrough) {
        this.id = id;
        this.providerId = providerId;
        this.listingId = listingId;
        this.budgetCents = budgetCents;
        this.clickPriceCents = clickPriceCents;
        this.impressionPriceCents = impressionPriceCents;
        this.currency = Currencies.normalizeOrDefault(currency, Currencies.DEFAULT_CODE);
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.billedThrough = billedThrough;
    }

    /**
     * The campaign's birth — ACTIVE from the first instant. The billing
     * marker starts at the start date's NEXT UTC day: the birth day itself
     * is unbilled (CodeRabbit W5 r1, adopted — the daily-grain honesty:
     * listing_views_daily counts whole days, so a mid-day birth cannot be
     * separated from the pre-birth views of the same day — charging them
     * would bill impressions the campaign never bought. The birth day is
     * therefore FREE, the provider-favorable direction, the same
     * daily-grain forfeit the pause day already carries in this design).
     */
    static AdCampaign start(UUID providerId, UUID listingId, long budgetCents,
                            long clickPriceCents, long impressionPriceCents, String currency,
                            Instant now, Instant endsAt) {
        return new AdCampaign(UUID.randomUUID(), providerId, listingId, budgetCents,
                clickPriceCents, impressionPriceCents, currency, now, endsAt,
                LocalDate.ofInstant(now, java.time.ZoneOffset.UTC).plusDays(1));
    }

    /** The owner's hold — no boost, no accrual; the paused gap never bills. */
    void pause() {
        if (this.status != AdCampaignStatus.ACTIVE) {
            throw new IllegalStateException("Only an ACTIVE campaign can pause: " + this.status);
        }
        this.status = AdCampaignStatus.PAUSED;
    }

    /**
     * The hold lifts — and the billing marker jumps past the paused gap:
     * the resume day itself is the first unsettled billable date, so the
     * dark days in between can never reach a charge row (the daily-grain
     * trade documented in the migration: the pause day itself is forfeit).
     */
    void resume(LocalDate today) {
        if (this.status != AdCampaignStatus.PAUSED) {
            throw new IllegalStateException("Only a PAUSED campaign can resume: " + this.status);
        }
        this.status = AdCampaignStatus.ACTIVE;
        if (today.isAfter(this.billedThrough)) {
            this.billedThrough = today;
        }
    }

    /** The duration's own end — the billing run's decision, not the owner's. */
    void endByDuration() {
        if (this.status == AdCampaignStatus.ENDED) {
            return;
        }
        this.status = AdCampaignStatus.ENDED;
    }

    /**
     * The frozen charge's ledger-side move: the amount lands on
     * {@code consumedCents} in the SAME transaction the immutable charge
     * row is inserted. The campaign ends the moment the budget is fully
     * consumed («حملة بميزانية تنتهي بنفادها») — the caller's capped
     * amount guarantees the column never exceeds the ceiling (the CHECK
     * is the backstop).
     */
    void consume(long amountCents) {
        this.consumedCents += amountCents;
        if (this.consumedCents >= this.budgetCents) {
            this.status = AdCampaignStatus.ENDED;
        }
    }

    /** The window just frozen — the marker's atomic advance (the charge's own transaction). */
    void markBilledThrough(LocalDate nextWindowStart) {
        if (nextWindowStart.isAfter(this.billedThrough)) {
            this.billedThrough = nextWindowStart;
        }
    }

    boolean hasRemainingBudget() {
        return this.consumedCents < this.budgetCents;
    }

    UUID getProviderId() { return providerId; }
    UUID getListingId() { return listingId; }
    long getBudgetCents() { return budgetCents; }
    long getClickPriceCents() { return clickPriceCents; }
    long getImpressionPriceCents() { return impressionPriceCents; }
    long getConsumedCents() { return consumedCents; }
    String getCurrency() { return currency; }
    AdCampaignStatus getStatus() { return status; }
    Instant getStartsAt() { return startsAt; }
    Instant getEndsAt() { return endsAt; }
    LocalDate getBilledThrough() { return billedThrough; }

    @Override
    public UUID getId() { return id; }
}
