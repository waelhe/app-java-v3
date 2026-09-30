package com.marketplace.reviews.spi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I7 gate b-3 (account-pseudonymization-plan §2) — the free-text purge
 * contract: four idempotent UPDATEs (the two live tables and their
 * {@code reviews_aud} mirrors), the exact summed count, and the
 * authorship predicates the port promises — a review's own text by
 * {@code reviewer_id}, a reply by {@code provider_id}.
 */
class ReviewsContentPurgeAdapterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private ReviewsContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReviewsContentPurgeAdapter(jdbcTemplate);
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
}