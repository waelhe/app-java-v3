package com.marketplace.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the recorded
 * click — the plan's «نقرة مسجلة». The public promoted-result click's
 * counter: {@link ListingViewCounter}'s contract verbatim (the keyed
 * HmacSHA256 visitor fingerprint under the SAME
 * {@code marketplace.catalog.views.ip-hash-key} — one fingerprint key per
 * module for the same CWE-759 purpose; the Redis {@code SET NX EX}
 * first-writer-wins marker; the undercount-never-inflate-never-break
 * degradation policy).
 *
 * <p><b>Why the dedup matters more here than for views:</b> a click
 * bills the advertiser's budget — click inflation is the classic
 * competitor attack on a paid channel (drain the rival's budget with
 * cheap clicks). The one-click-per-visitor-per-window marker is the
 * platform's structural first defense, exactly the anti-abuse posture
 * the plan's §4.5 set for the organic reviews.</p>
 *
 * <p><b>Attribution:</b> the click resolves the listing's ONE active
 * campaign (the partial unique index guarantees at most one) — a click
 * on an unpromoted listing answers the honest 404 no-op at the
 * controller; a click while the campaign is PAUSED or ENDED is not
 * recorded (the dark campaign accrues nothing).</p>
 */
@Component
public class AdClickCounter {

    private static final Logger log = LoggerFactory.getLogger(AdClickCounter.class);

    /** Redis key namespace: catalog's ad-click dedup markers (ephemeral, TTL'd). */
    static final String DEDUP_KEY_PREFIX = "catalog:ads:click-dedup:";

    /** One-time warning for the counting-off deployment state (never per-click log spam). */
    private final AtomicBoolean countingOffWarned = new AtomicBoolean();

    private final StringRedisTemplate redisTemplate;
    private final AdClicksDailyService clicksService;
    private final AdCampaignRepository campaignRepository;
    private final CatalogProperties properties;

    public AdClickCounter(StringRedisTemplate redisTemplate,
                          AdClicksDailyService clicksService,
                          AdCampaignRepository campaignRepository,
                          CatalogProperties properties) {
        this.redisTemplate = redisTemplate;
        this.clicksService = clicksService;
        this.campaignRepository = campaignRepository;
        this.properties = properties;
    }

    /**
     * Records one promoted-result click, total (never throws): resolve the
     * listing's live campaign OUTSIDE the failure boundary — attribution is
     * a read, and the class's own contract says a counting failure must
     * never change the caller's response (CodeRabbit W5 r1, adopted: the
     * earlier shape returned empty on a Redis/DB hiccup and the controller
     * 404'd a promoted listing). Then dedup the visitor, then the +1 with
     * the insert-race retry; any data-access failure in THAT step degrades
     * the count alone — the campaign is still attributed.
     *
     * <p>A campaign whose duration already ended is not clickable even
     * before the daily run flips it ENDED (the sub-day gap the run's own
     * cadence leaves — the same endsAt check the boost's EXISTS carries).
     *
     * @return the campaign the click was attributed to — empty when the
     *         listing has no live campaign (the controller's honest 404)
     */
    public Optional<UUID> recordClick(UUID listingId, String clientIp) {
        Optional<AdCampaign> campaign =
                campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(listingId, AdCampaignStatus.ACTIVE);
        if (campaign.isEmpty() || !campaign.get().hasRemainingBudget()
                || durationEnded(campaign.get())) {
            return Optional.empty();
        }
        UUID campaignId = campaign.get().getId();
        try {
            String key = properties.views().ipHashKey();
            if (key == null || key.isBlank()) {
                warnCountingOffOnce("no fingerprint key is configured "
                        + "(marketplace.catalog.views.ip-hash-key)");
                return Optional.of(campaignId); // attributed, uncounted (undercount, never inflate)
            }
            String fingerprint = ListingViewCounter.hashIp(key, clientIp);
            if (fingerprint == null) {
                return Optional.of(campaignId); // no remote address — the same unavailability rule
            }
            if (markFirstClick(campaignId, fingerprint)) {
                addClickWithInsertRaceRetry(campaignId, LocalDate.now(ZoneOffset.UTC));
            }
            return Optional.of(campaignId);
        } catch (DataAccessException countingFailure) {
            log.warn("Ad click counting degraded — the caller's response is unaffected "
                            + "(listingId={}): {}", listingId, countingFailure.getMessage());
            return Optional.of(campaignId); // attributed, uncounted — the contract's own rule
        }
    }

    /** The duration's own end, at click time — the sub-day gap the daily run cannot close. */
    private static boolean durationEnded(AdCampaign campaign) {
        return campaign.getEndsAt() != null && !campaign.getEndsAt().isAfter(java.time.Instant.now());
    }

    private void warnCountingOffOnce(String cause) {
        if (countingOffWarned.compareAndSet(false, true)) {
            log.warn("Ad click counting is OFF — {}; the public surface is unaffected. "
                    + "Production fails startup on a blank key (CatalogConfig); this "
                    + "deployment chose to run without ad click analytics.", cause);
        }
    }

    /**
     * The first-writer-wins marker: SET NX EX. True = this is the first
     * counted click of this (visitor, campaign) inside the window.
     */
    private boolean markFirstClick(UUID campaignId, String fingerprint) {
        String key = DEDUP_KEY_PREFIX + campaignId + ":" + fingerprint;
        Duration window = properties.ads().clickDedupWindow();
        Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", window);
        return Boolean.TRUE.equals(first);
    }

    /**
     * The +1 with the exactly-one retry the insert race needs —
     * {@code AdClicksDailyService}'s javadoc for why the retry must be a
     * NEW transaction and why one suffices by PostgreSQL semantics.
     */
    private void addClickWithInsertRaceRetry(UUID campaignId, LocalDate clickDate) {
        try {
            clicksService.addClick(campaignId, clickDate);
        } catch (DataIntegrityViolationException lostInsertRace) {
            clicksService.addClick(campaignId, clickDate);
        }
    }
}
