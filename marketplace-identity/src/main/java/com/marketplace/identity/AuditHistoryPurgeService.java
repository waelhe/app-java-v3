package com.marketplace.identity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.jpa.AuditColumnScrubAdapter;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-4 — the audit
 * history purge, executed on the user's gate-opening word): the identity
 * module's orchestration. The plan's purge option, verbatim: "حذف صفوف
 * {@code users_aud} للمستخدم (WHERE id=…) + كنس {@code created_by}/
 * {@code updated_by} عبر نحو 20 جدولاً — عملية ثقيلة تُشغَّل خارج
 * المعاملة، تقبل التدرّج".
 *
 * <p><b>The order is load-bearing (measured, not stylistic):</b> the
 * original raw subject — the very string the audit columns carry
 * (AuditorAwareImpl writes {@code Authentication.getName()}) — survives
 * pseudonymization ONLY in the {@code users_aud} revisions (the plan's
 * P6 residual; the users row now holds the derived replacement, and the
 * structured log line never recorded the original — CWE-532). So the
 * sequence must be: (1) recover the subject closure from the mirror
 * history, (2) scrub the audit columns across every discovered table,
 * (3) delete the {@code users_aud} rows. Deleting first would orphan the
 * closure and leave the original subject alive in {@code created_by}
 * forever — a silent debt, the exact shape this purge exists to close.</p>
 *
 * <p><b>Deliberately no transaction (the plan's "خارج المعاملة" +
 * gradualism):</b> the scrub runs one autocommitted statement per
 * (table, column) and the deletion is its own statement — a partial
 * failure leaves the completed statements committed and the re-run
 * resumes: every predicate is idempotent (NULL matches nothing) and a
 * user with no mirror history left answers zero. This service carries no
 * {@code @Transactional}, and the module surface suspends any ambient
 * transaction ({@code NOT_SUPPORTED} on the delegation).</p>
 *
 * <p><b>The guard:</b> the same erasure-flow contract as the b-3 purge —
 * the target must already be pseudonymized (409 otherwise, before any
 * statement). The raw subject dies with Phase 1's act; this purge cleans
 * the residuals the plan declared (P5/P6), it never operates on a live
 * account's audit trail.</p>
 *
 * <p><b>CWE-532 discipline:</b> the structured log line carries the
 * target (userId), actor, reason and the two counts — the subject
 * strings themselves never enter the log store (they are the direct
 * identifiers being scrubbed).</p>
 */
@Service
public class AuditHistoryPurgeService {

    private static final Logger log = LoggerFactory.getLogger(AuditHistoryPurgeService.class);

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditColumnScrubAdapter auditColumnScrubAdapter;

    public AuditHistoryPurgeService(
            UserRepository userRepository,
            JdbcTemplate jdbcTemplate,
            AuditColumnScrubAdapter auditColumnScrubAdapter) {
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.auditColumnScrubAdapter = auditColumnScrubAdapter;
    }

    /**
     * Purges the (already-pseudonymized) subject's audit identity: nulls
     * every {@code created_by}/{@code updated_by} cell holding one of his
     * subject strings across every table the live schema reports carrying
     * the columns, then deletes his {@code users_aud} revisions wholesale.
     * A re-run answers zero on both counts (NULL matches nothing; the
     * mirror history is gone).
     *
     * @throws ResourceNotFoundException no users row for the id
     * @throws ConflictException          the account is not pseudonymized
     *                                    — this purge completes an erasure
     *                                    flow (pseudonymize first)
     */
    @Observed(name = "user.audit.purge")
    public com.marketplace.identity.spi.AuditHistoryPurgeResult purge(UUID userId, String reason, String actor) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        if (user.getPseudonymizedAt() == null) {
            throw new ConflictException(
                    "Audit history purge requires a pseudonymized account — pseudonymize first "
                            + "(the purge completes an erasure flow; a live account's audit trail "
                            + "is active history)");
        }

        // (1) The subject closure — BEFORE anything destructive consumes it:
        // the mirror history's distinct subjects (the original raw subject
        // plus the derived replacement) united with the current row's
        // subject.
        Set<String> subjects = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT subject FROM users_aud WHERE id = ?", String.class, userId));
        subjects.add(user.getSubject());

        // (2) The scrub — every discovered table, one autocommitted
        // statement per (table, column).
        int scrubbedRows = auditColumnScrubAdapter.scrubAuditColumns(subjects);

        // (3) The mirror dies wholesale (the plan's letter — the deletion
        // subsumes scrubbing those rows).
        int usersAudRowsDeleted = jdbcTemplate.update(
                "DELETE FROM users_aud WHERE id = ?", userId);

        log.info("Audit history purge: userId={}, actor={}, reason='{}', scrubbedRows={}, "
                        + "usersAudRowsDeleted={}",
                userId, actor, reason, scrubbedRows, usersAudRowsDeleted);
        return new com.marketplace.identity.spi.AuditHistoryPurgeResult(scrubbedRows, usersAudRowsDeleted);
    }
}
