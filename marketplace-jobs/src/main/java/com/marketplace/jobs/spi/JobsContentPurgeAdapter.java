package com.marketplace.jobs.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Row 29 (agent findings 2026-10-25 — the tenth converter, the jobs module's
 * missing purge adapter): the jobs module's implementation of the
 * {@link AuthoredContentPurgePort} cross-module contract. Before this
 * adapter, the orchestrated purge completed without erasing the employment
 * vertical's authored free texts: a subsequent employer inbox read
 * ({@code JobsService.jobInbox}) still returned the seeker's original cover
 * message, and the public board/detail projections ({@code JobsService
 * .searchBoard}/{@code getJob}) still returned the employer's original
 * listing text — a live violation of the purge contract's erasure promise
 * (the finding's measured reachability chain, base + audit mirrors).
 *
 * <p><b>The authorship predicates (measured, the A1 convention):</b> the
 * module stores its two free texts under the caller's own user id at
 * creation time — {@code jobs.employer_id} ({@code JobsService.create} via
 * {@code CurrentUserProvider.getCurrentUserId}; V153's own comment: "The
 * employer's USER id (users.id space — the A1 convention)") and
 * {@code job_applications.seeker_id} ({@code JobsService.apply}, same
 * seam). One predicate each, direction-independent by construction: the
 * employer's listing texts never match the seeker's predicate and the
 * seeker's cover messages never match the employer's — the counterparty's
 * texts survive untouched (Art. 17(3)(b) / 20(4), the plan's §4 refusal of
 * hard deletes keeps the shared records alive in purged form).</p>
 *
 * <p><b>Schema facts (measured, V153):</b> all three purged columns are
 * NOT NULL — {@code jobs.title VARCHAR(200) NOT NULL},
 * {@code jobs.description TEXT NOT NULL} and
 * {@code job_applications.cover_message TEXT NOT NULL} — so every purge
 * writes the shared {@link AuthoredContentPurgePort#PURGED_MARKER}
 * tombstone (the honest representation the constraints admit; the
 * nullable-→NULL half of the port contract has no nullable column to apply
 * to here). {@code jobs_aud} and {@code job_applications_aud} (the V24
 * convention mirrors created with the tables in V153) carry the same
 * columns and the same authorship keys, so the mirrors purge with the same
 * predicates — a purge that left the mirrors intact would be cosmetic (the
 * original texts survive in the audit trail, a silent debt).</p>
 *
 * <p><b>Soft-deleted rows are in scope by construction:</b> both tables
 * ride Hibernate's {@code @SoftDelete(is_deleted)} through
 * {@code BaseEntity}, but the native JDBC UPDATE bypasses the soft-delete
 * filter entirely — a withdrawn application's cover message (the row the
 * seeker soft-deleted through {@code JobsService.withdraw}) is purged with
 * every live one, exactly the finding's "including soft-deleted rows"
 * requirement. No {@code is_deleted} predicate appears in any statement —
 * its absence IS the requirement.</p>
 *
 * <p><b>What stays (the row's own scope, the shared-record basis):</b> the
 * rows themselves and every non-text column — the job's
 * employment/workplace type, city/district (location classification, the
 * catalog category's discipline: structured facets, not authored prose),
 * the salary block, status and deadline; the application's job link,
 * status and lifecycle. Reference integrity for the applications that
 * reference the job and the counterparty's history keep the records alive
 * in purged form.</p>
 *
 * <p><b>Statement shape (the port's contract, the house pattern):</b>
 * native JDBC UPDATE, idempotent by the {@code <> marker} filters — exact
 * counts, zero matches on re-run; no Envers revision for the purge itself
 * (the orchestrator's structured log line is the audit record). One
 * transaction for the whole module adapter (the plan's §2 b-3 gradualism:
 * a mid-run failure in another module leaves this module committed and the
 * re-run resumes).</p>
 */
@Component
public class JobsContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(JobsContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public JobsContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        // The employer's authored listing texts (jobs + its audit mirror).
        int jobTitles = jdbcTemplate.update(
                "UPDATE jobs SET title = ? WHERE employer_id = ? "
                        + "AND title IS NOT NULL AND title <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int jobDescriptions = jdbcTemplate.update(
                "UPDATE jobs SET description = ? WHERE employer_id = ? "
                        + "AND description IS NOT NULL AND description <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int jobTitleAudits = jdbcTemplate.update(
                "UPDATE jobs_aud SET title = ? WHERE employer_id = ? "
                        + "AND title IS NOT NULL AND title <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int jobDescriptionAudits = jdbcTemplate.update(
                "UPDATE jobs_aud SET description = ? WHERE employer_id = ? "
                        + "AND description IS NOT NULL AND description <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        // The seeker's authored cover messages (job_applications + its
        // audit mirror) — soft-deleted rows included by the native UPDATE's
        // bypass of the soft-delete filter.
        int coverMessages = jdbcTemplate.update(
                "UPDATE job_applications SET cover_message = ? WHERE seeker_id = ? "
                        + "AND cover_message IS NOT NULL AND cover_message <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int coverMessageAudits = jdbcTemplate.update(
                "UPDATE job_applications_aud SET cover_message = ? WHERE seeker_id = ? "
                        + "AND cover_message IS NOT NULL AND cover_message <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int total = jobTitles + jobDescriptions + jobTitleAudits + jobDescriptionAudits
                + coverMessages + coverMessageAudits;
        log.info("Jobs content purge: userId={}, jobTitles={}, jobDescriptions={}, "
                        + "jobAuditRows={}, coverMessages={}, coverMessageAuditRows={}",
                userId, jobTitles, jobDescriptions, jobTitleAudits + jobDescriptionAudits,
                coverMessages, coverMessageAudits);
        return total;
    }
}
