package com.marketplace.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * L40 (realestate systems plan §5 — view analytics): the public-read-point
 * counter. One call per successful public listing detail read; the
 * noise-reduction contract is the plan's own wording — "زيارة واحدة لكل
 * (زائر/لوحة/يوم) بمعيار بصمة الزائر في الذاكرة (Redis TTL يوم)".
 *
 * <p><b>The fingerprint:</b> the keyed HmacSHA256 hex of the client IP
 * under {@code marketplace.catalog.views.ip-hash-key} — the L34 leads
 * fingerprint discipline (CWE-759: the IPv4 space is enumerable, a bare
 * digest is reversible by brute force, a keyed HMAC is not). It never
 * leaves the process: its only use is inside the ephemeral Redis dedup
 * key, which carries the 24h TTL — no permanent identifier is stored for
 * the anonymous visitor (the plan's privacy-by-design, no new b-5). In
 * production the IP is the real client address because
 * {@code server.forward-headers-strategy: FRAMEWORK} is set
 * (application-prod.yml — the ForwardedHeaderFilter rewrites
 * {@code getRemoteAddr()} from X-Forwarded-For behind the TLS-terminating
 * proxy; the same seam the leads fingerprint rides).
 *
 * <p><b>The unavailability policy (one rule, every cause):</b> when the
 * approximation layer is unavailable — Redis down, database down, no
 * fingerprint key configured, no remote address — the counter
 * UNDERCOUNTS (skips the view), never inflates (counting without dedup
 * turns one visitor's burst into N visits), and never breaks the read.
 * The count is a business signal; a deterministic bias direction is the
 * honest degradation. Production carries the key by the CatalogConfig
 * fail-fast; a deployment without it gets analytics OFF with a one-time
 * warning, not a poisoned count and not a broken public surface.
 *
 * <p><b>The dedup marker:</b> Redis {@code SET key "1" NX EX <window>} —
 * {@code StringRedisTemplate.setIfAbsent(key, value, ttl)}, the atomic
 * first-writer-wins primitive (two concurrent reads of one visitor cannot
 * both win the marker). This is the codebase's FIRST direct Redis usage
 * outside the Spring Cache/Session abstractions — deliberate: the cache
 * abstraction's GET/PUT round-trip is not atomic, so two concurrent
 * misses would both count, defeating the marker's entire purpose. The
 * window is fixed from the FIRST view (no sliding renewal), so one
 * visitor counts at most once per rolling 24h — the plan's documented
 * "TTL يوم" choice, an honest approximation of the calendar day.
 *
 * <p><b>Never breaks the read (the analytics contract):</b> counting is
 * best-effort by design — a Redis or database failure during counting
 * degrades the count (undercounting: the outage's missed views are
 * skipped, never inflated), logged as a warning, and the public read the
 * caller already holds returns untouched. The catch is the narrow Spring
 * {@link DataAccessException} hierarchy (the official unified data-access
 * exception family covering both the Redis and JPA paths), never a
 * blanket {@code Exception} — the house "no catch(Exception)" rule for
 * listener retries targets framework-managed retry, this is a
 * read-path-resilience decision with a guard test proving the behavior.
 */
@Component
public class ListingViewCounter {

    private static final Logger log = LoggerFactory.getLogger(ListingViewCounter.class);

    /** Redis key namespace: catalog's view-dedup markers (ephemeral, TTL'd). */
    static final String DEDUP_KEY_PREFIX = "catalog:views:dedup:";

    /** One-time warning for the analytics-off deployment state (never per-read log spam). */
    private final AtomicBoolean analyticsOffWarned = new AtomicBoolean();

    private final StringRedisTemplate redisTemplate;
    private final ListingViewsDailyService viewsService;
    private final CatalogProperties properties;

    public ListingViewCounter(StringRedisTemplate redisTemplate,
                              ListingViewsDailyService viewsService,
                              CatalogProperties properties) {
        this.redisTemplate = redisTemplate;
        this.viewsService = viewsService;
        this.properties = properties;
    }

    /**
     * Records one public-detail view: dedup first, then the +1 with the
     * insert-race retry. Total — never throws; any data-access failure or
     an unavailable approximation layer degrades the count, not the read.
     */
    public void recordView(UUID listingId, String clientIp) {
        try {
            String key = properties.views().ipHashKey();
            if (key == null || key.isBlank()) {
                warnAnalyticsOffOnce("no fingerprint key is configured "
                        + "(marketplace.catalog.views.ip-hash-key)");
                return; // undercount, never inflate, never break (the class javadoc)
            }
            String fingerprint = hashIp(key, clientIp);
            if (fingerprint == null) {
                return; // no remote address — the same unavailability rule
            }
            if (markFirstVisit(listingId, fingerprint)) {
                addViewWithInsertRaceRetry(listingId, LocalDate.now(ZoneOffset.UTC));
            }
        } catch (DataAccessException analyticsFailure) {
            log.warn("Listing view counting degraded — the public read is unaffected "
                            + "(listingId={}): {}", listingId, analyticsFailure.getMessage());
        }
    }

    private void warnAnalyticsOffOnce(String cause) {
        if (analyticsOffWarned.compareAndSet(false, true)) {
            log.warn("Listing view counting is OFF — {}; the public read is unaffected. "
                    + "Production fails startup on a blank key (CatalogConfig); this "
                    + "deployment chose to run without analytics.", cause);
        }
    }

    /**
     * The first-writer-wins marker: SET NX EX. True = this is the first
     * counted visit of this (visitor, listing) inside the window.
     */
    private boolean markFirstVisit(UUID listingId, String fingerprint) {
        String key = DEDUP_KEY_PREFIX + listingId + ":" + fingerprint;
        Duration window = properties.views().dedupWindow();
        Boolean first = redisTemplate.opsForValue().setIfAbsent(key, "1", window);
        return Boolean.TRUE.equals(first);
    }

    /**
     * The +1 with the exactly-one retry the insert race needs — see
     * {@link ListingViewsDailyService}'s javadoc for why the retry must be
     * a NEW transaction and why one suffices by PostgreSQL semantics.
     */
    private void addViewWithInsertRaceRetry(UUID listingId, LocalDate viewDate) {
        try {
            viewsService.addView(listingId, viewDate);
        } catch (DataIntegrityViolationException lostInsertRace) {
            viewsService.addView(listingId, viewDate);
        }
    }

    /**
     * The keyed fingerprint: HmacSHA256 hex of the client IP under the
     * configured key — byte-for-byte the L34 {@code LeadsService.hashIp}
     * discipline (CWE-759), catalog-owned so the modules' keys rotate
     * independently. Null when no address is present (the test seam); a
     * blank CONFIGURED key fails loudly by name — though the prod
     * fail-fast (CatalogConfig) makes that unreachable in production.
     */
    static String hashIp(String key, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return null;
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "marketplace.catalog.views.ip-hash-key must be configured —"
                            + " the view visitor fingerprint is a keyed HMAC (CWE-759)");
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(clientIp.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandated by the Java platform specification — unreachable.
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
