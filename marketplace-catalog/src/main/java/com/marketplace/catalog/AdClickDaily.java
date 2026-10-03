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
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the recorded
 * click — the plan's «نقرة مسجلة», the click twin of
 * {@code listing_views_daily} (V58's exact shape: same daily grain, same
 * UNIQUE (campaign, date), same count CHECK, same pessimistic-locked
 * read-modify-write increment with the insert-race retry the service
 * owns).
 *
 * <p>A click is attributed to the campaign that was LIVE when it
 * happened: the public endpoint resolves the listing's one ACTIVE
 * campaign (the partial unique index guarantees at most one), so a click
 * on an unpromoted listing is the honest 404 no-op. The visitor dedup is
 * the views counter's own recipe — Redis {@code SET NX EX} marker under
 * the keyed HMAC IP fingerprint (CWE-759) — so a repeat click within the
 * window bills once, the same anti-inflation defense the views own
 * (click fraud is the advertiser's budget drain; the dedup is the
 * platform's structural answer).</p>
 */
@Entity
@Table(name = "ad_clicks_daily",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uk_ad_clicks_daily_campaign_date",
                columnNames = {"campaign_id", "click_date"}))
@Audited
public class AdClickDaily extends BaseEntity {

    /** A row exists only because at least one deduplicated click happened (CHECK click_count >= 1). */
    static final long FIRST_CLICK_COUNT = 1L;

    @Id
    private UUID id;

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    /** The UTC day of the counted clicks. */
    @Column(name = "click_date", nullable = false)
    private LocalDate clickDate;

    /** That day's deduplicated click count — monotonic, >= 1 by the CHECK. */
    @Column(name = "click_count", nullable = false)
    private long clickCount;

    protected AdClickDaily() {
        // JPA
    }

    private AdClickDaily(UUID id, UUID campaignId, LocalDate clickDate, long clickCount) {
        this.id = id;
        this.campaignId = campaignId;
        this.clickDate = clickDate;
        this.clickCount = clickCount;
    }

    /** The first deduplicated click of one campaign-day — the row the unique constraint guards. */
    static AdClickDaily firstClick(UUID campaignId, LocalDate clickDate) {
        return new AdClickDaily(UUID.randomUUID(), campaignId, clickDate, FIRST_CLICK_COUNT);
    }

    /** The atomic +1 — called with the row locked (the ListingViewsDaily contract verbatim). */
    void addClick() {
        this.clickCount++;
    }

    UUID getCampaignId() { return campaignId; }
    LocalDate getClickDate() { return clickDate; }
    long getClickCount() { return clickCount; }

    @Override
    public UUID getId() { return id; }
}
