package com.marketplace.jobs.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Row 29 (agent findings 2026-10-25 — the tenth converter): the jobs
 * module's free-text purge contract. Six idempotent UPDATEs (jobs'
 * title/description and their {@code jobs_aud} mirror keyed by
 * {@code employer_id}; job_applications' cover_message and its
 * {@code job_applications_aud} mirror keyed by {@code seeker_id}), the
 * exact summed count, and the authorship predicates the port promises —
 * the listing texts belong to the employer, the cover messages to the
 * seeker, and no statement filters on {@code is_deleted} (native SQL
 * bypasses the soft-delete filter: withdrawn applications purge with the
 * live ones — the finding's "including soft-deleted rows" requirement).
 */
class JobsContentPurgeAdapterTest {

    private static final UUID SUBJECT = UUID.randomUUID();
    private static final UUID COUNTERPARTY = UUID.randomUUID();

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private JobsContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JobsContentPurgeAdapter(jdbcTemplate);
    }

    private void stubCounts(int jobTitles, int jobDescriptions, int jobTitleAudits,
                            int jobDescriptionAudits, int coverMessages, int coverMessageAudits) {
        when(jdbcTemplate.update(contains("UPDATE jobs SET title"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(jobTitles);
        when(jdbcTemplate.update(contains("UPDATE jobs SET description"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(jobDescriptions);
        when(jdbcTemplate.update(contains("UPDATE jobs_aud SET title"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(jobTitleAudits);
        when(jdbcTemplate.update(contains("UPDATE jobs_aud SET description"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(jobDescriptionAudits);
        when(jdbcTemplate.update(contains("UPDATE job_applications SET cover_message"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(coverMessages);
        when(jdbcTemplate.update(contains("UPDATE job_applications_aud SET cover_message"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(coverMessageAudits);
    }

    @Test
    void purgeAuthoredTexts_sumsEveryAffectedRow() {
        stubCounts(2, 2, 2, 2, 3, 3);

        assertEquals(14, adapter.purgeAuthoredTexts(SUBJECT));
    }

    @Test
    void purgeAuthoredTexts_purgesTheListingTextsTheSubjectAuthoredAsEmployer() {
        stubCounts(0, 0, 0, 0, 0, 0);

        adapter.purgeAuthoredTexts(SUBJECT);

        // all three columns are NOT NULL (V153) — the marker is the
        // honest representation the constraint admits (the port contract).
        verify(jdbcTemplate).update(
                "UPDATE jobs SET title = ? WHERE employer_id = ? "
                        + "AND title IS NOT NULL AND title <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, SUBJECT, AuthoredContentPurgePort.PURGED_MARKER);
        verify(jdbcTemplate).update(
                "UPDATE jobs SET description = ? WHERE employer_id = ? "
                        + "AND description IS NOT NULL AND description <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, SUBJECT, AuthoredContentPurgePort.PURGED_MARKER);
    }

    @Test
    void purgeAuthoredTexts_purgesTheCoverMessagesTheSubjectSubmittedAsSeeker() {
        stubCounts(0, 0, 0, 0, 0, 0);

        adapter.purgeAuthoredTexts(SUBJECT);

        verify(jdbcTemplate).update(
                "UPDATE job_applications SET cover_message = ? WHERE seeker_id = ? "
                        + "AND cover_message IS NOT NULL AND cover_message <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, SUBJECT, AuthoredContentPurgePort.PURGED_MARKER);
    }

    @Test
    void purgeAuthoredTexts_mirrorsThePurgeIntoBothAuditTables() {
        stubCounts(0, 0, 0, 0, 0, 0);

        adapter.purgeAuthoredTexts(SUBJECT);

        verify(jdbcTemplate).update(
                "UPDATE jobs_aud SET title = ? WHERE employer_id = ? "
                        + "AND title IS NOT NULL AND title <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, SUBJECT, AuthoredContentPurgePort.PURGED_MARKER);
        verify(jdbcTemplate).update(
                "UPDATE jobs_aud SET description = ? WHERE employer_id = ? "
                        + "AND description IS NOT NULL AND description <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, SUBJECT, AuthoredContentPurgePort.PURGED_MARKER);
        verify(jdbcTemplate).update(
                "UPDATE job_applications_aud SET cover_message = ? WHERE seeker_id = ? "
                        + "AND cover_message IS NOT NULL AND cover_message <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, SUBJECT, AuthoredContentPurgePort.PURGED_MARKER);
    }

    @Test
    void purgeAuthoredTexts_neverMatchesTheCounterpartyPredicates() {
        stubCounts(0, 0, 0, 0, 0, 0);

        adapter.purgeAuthoredTexts(SUBJECT);

        // the employer predicate never carries the seeker space and vice
        // versa — the counterpart (another employer, another seeker) is
        // out of scope by the statement's own key (the reviews adapter's
        // counterparty-isolation discipline).
        verify(jdbcTemplate, org.mockito.Mockito.never()).update(
                org.mockito.ArgumentMatchers.contains("UPDATE jobs"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(COUNTERPARTY),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
        verify(jdbcTemplate, org.mockito.Mockito.never()).update(
                org.mockito.ArgumentMatchers.contains("UPDATE job_applications"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(COUNTERPARTY),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
    }

    @Test
    void purgeAuthoredTexts_isIdempotentByTheMarkerFilters() {
        stubCounts(0, 0, 0, 0, 0, 0);

        assertEquals(0, adapter.purgeAuthoredTexts(SUBJECT));
        assertEquals(0, adapter.purgeAuthoredTexts(SUBJECT));
    }
}
