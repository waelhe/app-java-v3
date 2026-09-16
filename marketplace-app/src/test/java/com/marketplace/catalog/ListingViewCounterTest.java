package com.marketplace.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L40 (realestate systems plan §5 — view analytics): the counter's own
 * contract — the noise-reduction arithmetic ("زيارة واحدة لكل
 * (زائر/لوحة/يوم) بمعيار بصمة الزائر في الذاكرة") and the analytics
 * contract (counting is total: a data-access failure degrades the count,
 * never the read). The Redis SETNX semantics, the insert-race retry and
 * the fingerprint discipline are pinned here at the unit level; the real
 * Redis/PostgreSQL chains are the integration test's.
 */
class ListingViewCounterTest {

    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final String IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.9";
    private static final Duration WINDOW = Duration.ofDays(1);
    private static final String KEY = "catalog:views:dedup:" + LISTING_ID + ":"
            + ListingViewCounter.hashIp("test-key", IP);

    @SuppressWarnings("unchecked")
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final ListingViewsDailyService viewsService = mock(ListingViewsDailyService.class);
    private final CatalogProperties properties = new CatalogProperties(
            new CatalogProperties.Expiry(90, 1),
            new CatalogProperties.Seo("", "/listings/{id}", java.util.List.of()),
            new CatalogProperties.Views("test-key", WINDOW));

    private ListingViewCounter counter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        return new ListingViewCounter(redisTemplate, viewsService, properties);
    }

    @Test
    void firstVisit_marksAndCounts() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(WINDOW))).thenReturn(true);

        counter().recordView(LISTING_ID, IP);

        // SET NX EX with the documented key shape: prefix + listing + fingerprint
        verify(valueOps).setIfAbsent(KEY, "1", WINDOW);
        verify(viewsService).addView(LISTING_ID, LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    void repeatVisitWithinTheWindow_isNotCountedAgain() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        counter().recordView(LISTING_ID, IP);

        verify(viewsService, never()).addView(any(), any());
    }

    @Test
    void anotherVisitor_isCountedIndependently() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        counter().recordView(LISTING_ID, IP);
        counter().recordView(LISTING_ID, OTHER_IP);

        // two different fingerprints → two different dedup keys → two counts
        verify(viewsService, times(2)).addView(eq(LISTING_ID), any());
    }

    @Test
    void nullRemoteAddress_skipsTheCount_neverInflates() {
        // The unified unavailability rule (the class javadoc): no address =
        // the approximation layer has no input — undercount (skip), never
        // inflate (count without dedup), never break.
        counter().recordView(LISTING_ID, null);

        verifyNoInteractions(redisTemplate);
        verify(viewsService, never()).addView(any(), any());
    }

    @Test
    void blankKey_skipsTheCount_onceWarned() {
        // The analytics-off deployment state: the key is blank, production
        // would have failed startup (CatalogConfig) — a non-prod run
        // degrades to analytics OFF, the read unaffected (one warning).
        CatalogProperties noKey = new CatalogProperties(
                new CatalogProperties.Expiry(90, 1),
                new CatalogProperties.Seo("", "/listings/{id}", java.util.List.of()),
                new CatalogProperties.Views("", WINDOW));
        ListingViewCounter counter = new ListingViewCounter(redisTemplate, viewsService, noKey);

        counter.recordView(LISTING_ID, IP);
        counter.recordView(LISTING_ID, OTHER_IP);

        verifyNoInteractions(redisTemplate);
        verify(viewsService, never()).addView(any(), any());
    }

    @Test
    void redisFailure_neverThrowsAndSkipsTheCount() {
        // The outage answer: undercount (missed views are skipped),
        // never inflate (count without dedup), never break the read.
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertThatCode(() -> counter().recordView(LISTING_ID, IP))
                .doesNotThrowAnyException();

        verify(viewsService, never()).addView(any(), any());
    }

    @Test
    void databaseFailure_neverThrows() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("database down"))
                .when(viewsService).addView(any(), any());

        assertThatCode(() -> counter().recordView(LISTING_ID, IP))
                .doesNotThrowAnyException();
    }

    @Test
    void insertRace_isRetriedExactlyOnce() {
        // The unique-constraint loser: PostgreSQL aborted that transaction,
        // the retry rides a NEW one and now finds the winner's row (the
        // service's own javadoc — one retry suffices by DB semantics).
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException(
                "uk_listing_views_daily_listing_date"))
                .doNothing()
                .when(viewsService).addView(any(), any());

        counter().recordView(LISTING_ID, IP);

        verify(viewsService, times(2)).addView(LISTING_ID, LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    void hashIp_isDeterministicPerKeyAndNeverTheRawAddress() {
        String first = ListingViewCounter.hashIp("test-key", IP);
        String second = ListingViewCounter.hashIp("test-key", IP);
        String otherKey = ListingViewCounter.hashIp("another-key", IP);

        assertThat(first).isEqualTo(second);           // deterministic — the marker survives restarts
        assertThat(first).hasSize(64);                 // HmacSHA256 hex
        assertThat(first).doesNotContain(IP);          // never the raw address
        assertThat(otherKey).isNotEqualTo(first);      // keyed, not bare — CWE-759
        assertThat(ListingViewCounter.hashIp("test-key", null)).isNull();
        assertThat(ListingViewCounter.hashIp("test-key", "  ")).isNull();
    }

    @Test
    void hashIp_blankConfiguredKey_failsLoudlyByName() {
        // Unreachable in prod (CatalogConfig fail-fast) and in the test
        // profile (application-test.yml pins the key) — the loud failure
        // is the last line of defense, never a silent empty-key HMAC.
        assertThatThrownBy(() -> ListingViewCounter.hashIp("", IP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marketplace.catalog.views.ip-hash-key");
    }
}
