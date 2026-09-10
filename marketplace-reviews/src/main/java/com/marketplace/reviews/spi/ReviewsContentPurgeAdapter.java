package com.marketplace.reviews.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 — the free-text
 * purge): the reviews module's implementation of the
 * {@link AuthoredContentPurgePort} cross-module contract. Purges both
 * review texts the subject authored:
 * <ul>
 *   <li>{@code comment} — the review text he wrote
 *       ({@code reviewer_id = ?}: the only writer is the review author,
 *       {@code ReviewsService.create}/{@code createReverse});</li>
 *   <li>{@code reply} — the reply he authored as the review's provider.
 *       Measured authorship (the only gate that exists,
 *       {@code ReviewsService.reply}: "Only the reviewed provider can
 *       reply" — the caller's profile must match {@code provider_id}):
 *       the reply author is {@code provider_id}'s user on EVERY row — on
 *       {@code CONSUMER_TO_PROVIDER} rows that is the reviewed provider;
 *       on {@code PROVIDER_TO_CONSUMER} rows {@code Review.createReverse}
 *       stores the AUTHORING provider in {@code provider_id} (its
 *       Javadoc's "profiles.id space" is the documented deviation — the
 *       runtime value is {@code users.id}, the A1 convention measured and
 *       documented in {@code AuthHelper.ownsProvider}: "every
 *       cross-module {@code provider_id} column carries a user id"). One
 *       direction-independent predicate therefore matches exactly his
 *       replies and never the counterparty's: the counterpart's reply on
 *       a forward review survives (its {@code provider_id} is the other
 *       party), and so does the provider's reply on a reverse review
 *       when the subject is the reviewed consumer
 *       ({@code reviewee_id}).</li>
 * </ul>
 *
 * <p><b>The live defect is CLOSED (the §9 surgical gate fix):</b>
 * {@code ReviewsService.reply}/{@code createReverse} now read the ruling
 * from the row itself — the stored {@code users.id} (A1 convention)
 * compared directly against the caller's user id, the
 * {@code verifyProviderOwnership} pattern (no profile resolution: the
 * {@code providerLookupPort.findByUserId(...).id()} resolution compared
 * users.id against {@code provider_profiles.id} — the cross-space
 * mismatch that denied the legitimate owner on every call). Replies and
 * reverse reviews are writable through the API again, and this
 * adapter's reply predicate guards live rows written through the fixed
 * path — the authorship model below is unchanged and remains the
 * contract: the reply author is {@code provider_id}'s user on every
 * row.</p>
 *
 * <p><b>Schema facts (measured, V6/V37/V45):</b> {@code comment} and
 * {@code reply} are nullable {@code text} — the purge writes NULL (the
 * Phase 1 storage convention). {@code reviews_aud} mirrors
 * {@code reviewer_id}, {@code provider_id}, {@code comment} and
 * {@code reply} (V24 §8 + V37's mirror alteration), so the mirror purges
 * with the same predicates.</p>
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE,
 * idempotent by the {@code IS NOT NULL} filters — exact counts, zero
 * matches on re-run; no Envers revision for the purge itself (the
 * orchestrator's structured log line is the audit record).</p>
 */
@Component
public class ReviewsContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(ReviewsContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public ReviewsContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        int comments = jdbcTemplate.update(
                "UPDATE reviews SET comment = NULL WHERE reviewer_id = ? AND comment IS NOT NULL",
                userId);
        int commentAudits = jdbcTemplate.update(
                "UPDATE reviews_aud SET comment = NULL WHERE reviewer_id = ? AND comment IS NOT NULL",
                userId);
        // The reply author is provider_id's user on every row (the reply
        // gate's own authorship model — see the Javadoc): one predicate,
        // both directions, never the counterparty's text.
        int replies = jdbcTemplate.update(
                "UPDATE reviews SET reply = NULL WHERE reply IS NOT NULL AND provider_id = ?",
                userId);
        int replyAudits = jdbcTemplate.update(
                "UPDATE reviews_aud SET reply = NULL WHERE reply IS NOT NULL AND provider_id = ?",
                userId);
        log.info("Reviews content purge: userId={}, comments={}, commentAuditRows={}, "
                        + "replies={}, replyAuditRows={}",
                userId, comments, commentAudits, replies, replyAudits);
        return comments + commentAudits + replies + replyAudits;
    }
}
