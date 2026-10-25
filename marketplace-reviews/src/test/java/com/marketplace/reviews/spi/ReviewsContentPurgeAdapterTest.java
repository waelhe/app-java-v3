package com.marketplace.reviews.spi;

import com.marketplace.shared.api.CacheInvalidationRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I7 gate b-3 (account-pseudonymization-plan §2) — the free-text purge
 * contract: four idempotent UPDATEs (the two live tables and their
 * {@code reviews_aud} mirrors), the exact summed count, and the
 * authorship predicates the port promises — a review's own text by
 * {@code reviewer_id}, a reply by {@code provider_id}.
 *
 * <p>Row 27 (CodeRabbit agent findings — the {@code ProviderProfileContentPurgeAdapter}
 * house pattern): the purge also resolves every affected review id and
 * publishes the TARGETED {@code "reviews"} cache invalidation — AFTER
 * COMMIT through the house relay — so the erased comment/reply texts are
 * never served from the cache to the public single-review read.</p>
 */
class ReviewsContentPurgeAdapterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private ReviewsContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReviewsContentPurgeAdapter(jdbcTemplate, eventPublisher);
    }

    @Test
    void purgeAuthoredTexts_sumsEveryAffectedRow() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.update(contains("UPDATE reviews SET comment"), eq(userId))).thenReturn(3);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET comment"), eq(userId))).thenReturn(3);
        when(jdbcTemplate.update(contains("UPDATE reviews SET reply"), eq(userId))).thenReturn(2);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET reply"), eq(userId))).thenReturn(2);

        assertEquals(10, adapter.purgeAuthoredTexts(userId));
    }

    @Test
    void purgeAuthoredTexts_purgesTheReviewTheSubjectAuthored() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.update(contains("UPDATE reviews SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews SET reply"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET reply"), eq(userId))).thenReturn(0);

        assertEquals(0, adapter.purgeAuthoredTexts(userId));

        verify(jdbcTemplate).update(
                "UPDATE reviews SET comment = NULL WHERE reviewer_id = ? AND comment IS NOT NULL",
                userId);
    }

    @Test
    void purgeAuthoredTexts_purgesTheReplyTheSubjectAuthored() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.update(contains("UPDATE reviews SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews SET reply"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET reply"), eq(userId))).thenReturn(0);

        adapter.purgeAuthoredTexts(userId);

        verify(jdbcTemplate).update(
                "UPDATE reviews SET reply = NULL WHERE reply IS NOT NULL AND provider_id = ?",
                userId);
        verify(jdbcTemplate).update(
                "UPDATE reviews_aud SET reply = NULL WHERE reply IS NOT NULL AND provider_id = ?",
                userId);
    }

    @Test
    void purgeAuthoredTexts_mirrorsThePurgeIntoTheAuditTable() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.update(contains("UPDATE reviews SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews SET reply"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET reply"), eq(userId))).thenReturn(0);

        adapter.purgeAuthoredTexts(userId);

        verify(jdbcTemplate).update(
                "UPDATE reviews_aud SET comment = NULL WHERE reviewer_id = ? AND comment IS NOT NULL",
                userId);
    }

    @Test
    void purgeAuthoredTexts_isIdempotentByTheNotNullFilters() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.update(contains("UPDATE reviews SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews SET reply"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET reply"), eq(userId))).thenReturn(0);

        assertEquals(0, adapter.purgeAuthoredTexts(userId));
        assertEquals(0, adapter.purgeAuthoredTexts(userId));
    }

    // -- Row 27: the affected-ids resolution and the targeted eviction ----

    @Test
    void purgeAuthoredTexts_resolvesBothAuthoredClassesBeforeTheUpdatesRun() {
        UUID userId = UUID.randomUUID();
        UUID commentReview = UUID.randomUUID();
        UUID replyReview = UUID.randomUUID();
        when(jdbcTemplate.queryForList(
                contains("SELECT id FROM reviews WHERE reviewer_id"), eq(UUID.class), eq(userId)))
                .thenReturn(List.of(commentReview));
        when(jdbcTemplate.queryForList(
                contains("SELECT id FROM reviews WHERE reply"), eq(UUID.class), eq(userId)))
                .thenReturn(List.of(replyReview));
        when(jdbcTemplate.update(contains("UPDATE reviews SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews SET reply"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET reply"), eq(userId))).thenReturn(0);

        adapter.purgeAuthoredTexts(userId);

        // Row 27 — the resolution runs BEFORE the UPDATEs: the IS NOT NULL
        // predicates that select this purge's rows are the same predicates
        // the UPDATEs null out, so resolving afterwards would match nothing
        // (the cache entry would outlive the purge it was meant to evict).
        var inOrder = org.mockito.Mockito.inOrder(jdbcTemplate, eventPublisher);
        inOrder.verify(jdbcTemplate).queryForList(
                "SELECT id FROM reviews WHERE reviewer_id = ? AND comment IS NOT NULL",
                UUID.class, userId);
        inOrder.verify(jdbcTemplate).queryForList(
                "SELECT id FROM reviews WHERE reply IS NOT NULL AND provider_id = ?",
                UUID.class, userId);
        inOrder.verify(jdbcTemplate).update(
                "UPDATE reviews SET comment = NULL WHERE reviewer_id = ? AND comment IS NOT NULL",
                userId);
    }

    @Test
    void purgeAuthoredTexts_publishesTheTargetedEvictionForEveryAffectedReview() {
        UUID userId = UUID.randomUUID();
        UUID commentReview = UUID.randomUUID();
        UUID replyReview = UUID.randomUUID();
        when(jdbcTemplate.queryForList(
                contains("SELECT id FROM reviews WHERE reviewer_id"), eq(UUID.class), eq(userId)))
                .thenReturn(List.of(commentReview));
        when(jdbcTemplate.queryForList(
                contains("SELECT id FROM reviews WHERE reply"), eq(UUID.class), eq(userId)))
                .thenReturn(List.of(replyReview));
        when(jdbcTemplate.update(contains("UPDATE reviews SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews SET reply"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET reply"), eq(userId))).thenReturn(0);

        adapter.purgeAuthoredTexts(userId);

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
        List<CacheInvalidationRequested> invalidations = events.getAllValues().stream()
                .filter(v -> v instanceof CacheInvalidationRequested)
                .map(v -> (CacheInvalidationRequested) v).toList();
        // One targeted eviction per affected review — the cache name and
        // the key the "reviews" cache (ReviewsService.getById, keyed by id
        // alone) reads — never a whole-cache clear.
        assertEquals(2, invalidations.size());
        assertEquals(1L, invalidations.stream().filter(inv ->
                inv.key().equals(commentReview)).count());
        assertEquals(1L, invalidations.stream().filter(inv ->
                inv.key().equals(replyReview)).count());
        org.junit.jupiter.api.Assertions.assertTrue(invalidations.stream().allMatch(inv ->
                inv.cacheNames().contains("reviews") && !inv.clearAll()));
    }

    @Test
    void purgeAuthoredTexts_aReviewCarryingBothTextsResolvesOnce() {
        UUID userId = UUID.randomUUID();
        UUID bothReview = UUID.randomUUID();
        // The same review carries the subject's comment AND his reply:
        // the set's own dedup resolves it once — one eviction, not two.
        when(jdbcTemplate.queryForList(
                contains("SELECT id FROM reviews WHERE reviewer_id"), eq(UUID.class), eq(userId)))
                .thenReturn(List.of(bothReview));
        when(jdbcTemplate.queryForList(
                contains("SELECT id FROM reviews WHERE reply"), eq(UUID.class), eq(userId)))
                .thenReturn(List.of(bothReview));
        when(jdbcTemplate.update(contains("UPDATE reviews SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews SET reply"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET reply"), eq(userId))).thenReturn(0);

        adapter.purgeAuthoredTexts(userId);

        verify(eventPublisher, org.mockito.Mockito.times(1)).publishEvent(
                org.mockito.ArgumentMatchers.argThat((Object ev) -> ev instanceof
                        CacheInvalidationRequested inv && inv.key().equals(bothReview)));
    }

    @Test
    void purgeAuthoredTexts_zeroAffectedPublishesNothing_neverClearsTheWholeCache() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(
                contains("SELECT id FROM reviews WHERE reviewer_id"), eq(UUID.class), eq(userId)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                contains("SELECT id FROM reviews WHERE reply"), eq(UUID.class), eq(userId)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(contains("UPDATE reviews SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET comment"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews SET reply"), eq(userId))).thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE reviews_aud SET reply"), eq(userId))).thenReturn(0);

        assertEquals(0, adapter.purgeAuthoredTexts(userId));

        // Nothing authored by the subject: zero evictions — and never a
        // whole-cache clear (the targeted-only discipline; a zero-resolution
        // purge would needlessly wipe every other user's cached reviews).
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}
