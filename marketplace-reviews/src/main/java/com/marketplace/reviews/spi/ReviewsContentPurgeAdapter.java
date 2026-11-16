package com.marketplace.reviews.spi;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import com.marketplace.shared.api.CacheInvalidationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * <p><b>Cache invalidation (row 27 — CodeRabbit agent findings, the
 * {@code ProviderProfileContentPurgeAdapter} house pattern verbatim):</b>
 * the purge runs as raw JDBC inside the orchestrator's transaction — no
 * entity write, so no service-level {@code CacheInvalidationRequested}
 * fires — and the {@code "reviews"} cache
 * ({@code ReviewsService.getById}, keyed by review id alone) would keep
 * serving the erased comment/reply texts to the public single-review
 * read. This adapter therefore resolves every affected review id —
 * BOTH authored classes: the subject's comments (reviewer_id) and his
 * replies (provider_id) — and publishes the TARGETED invalidation
 * through the house mechanism (the AFTER_COMMIT relay — the event fires
 * only after this transaction commits, so the eviction cannot race a
 * rollback). The resolution runs BEFORE the UPDATEs: the {@code IS NOT
 * NULL} predicates that select this purge's rows are the same
 * predicates the UPDATEs null out, so resolving afterwards would find
 * nothing and a cached entry could outlive the purge it was meant to
 * evict. The whole-cache clear alternative is deliberately NOT taken,
 * with the measured fact as the documented reason: a cache entry can
 * only exist for a row that resolved here (a read that populated the
 * cache read the row this purge targets), and a zero-resolution purge
 * (nothing authored by the subject) would needlessly wipe every other
 * user's cached reviews on each such erasure. Targeted evictions are
 * idempotent — the already-purged re-run resolves nothing and simply
 * publishes no event at all.</p>
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE,
 * idempotent by the {@code IS NOT NULL} filters — exact counts, zero
 * matches on re-run; no Envers revision for the purge itself (the
 * orchestrator's structured log line is the audit record).</p>
 */
@Component
public class ReviewsContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(ReviewsContentPurgeAdapter.class);

    private static final Set<String> REVIEWS_CACHE_NAMES = Set.of("reviews");

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public ReviewsContentPurgeAdapter(JdbcTemplate jdbcTemplate,
                                      ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        // Row 27 — the affected review ids resolve BEFORE the UPDATEs run:
        // the IS NOT NULL predicates that select this purge's rows are the
        // same predicates the UPDATEs null out, so resolving after would
        // match nothing and the "reviews" cache would keep serving the
        // erased texts. The union covers BOTH authored classes — the
        // subject's comments (reviewer_id) and his replies (provider_id) —
        // and a review carrying both (his comment, his reply) resolves
        // once (the set's own dedup).
        Set<UUID> affectedReviewIds = new LinkedHashSet<>();
        affectedReviewIds.addAll(jdbcTemplate.queryForList(
                "SELECT id FROM reviews WHERE reviewer_id = ? AND comment IS NOT NULL",
                UUID.class, userId));
        affectedReviewIds.addAll(jdbcTemplate.queryForList(
                "SELECT id FROM reviews WHERE reply IS NOT NULL AND provider_id = ?",
                UUID.class, userId));

        // Row 5 (agent findings 2026-10-26 14:33 export, CWE-362): the text
        // erasure ADVANCES the live row's optimistic-lock version in the
        // SAME statement — a provider reply holding a pre-erasure Review
        // snapshot fails its flush on the version the erasure just
        // advanced, instead of restoring the erased comment through the
        // passed check and recreating it in a new audit revision (the
        // booking/disputes/search purge adapters' own family). Both text
        // directions (the reviewer's comment, the provider's reply) carry
        // it; the audit mirrors carry no version column (Envers revisions).
        int comments = jdbcTemplate.update(
                "UPDATE reviews SET comment = NULL, version = version + 1 "
                        + "WHERE reviewer_id = ? AND comment IS NOT NULL",
                userId);
        int commentAudits = jdbcTemplate.update(
                "UPDATE reviews_aud SET comment = NULL WHERE reviewer_id = ? AND comment IS NOT NULL",
                userId);
        // The reply author is provider_id's user on every row (the reply
        // gate's own authorship model — see the Javadoc): one predicate,
        // both directions, never the counterparty's text.
        int replies = jdbcTemplate.update(
                "UPDATE reviews SET reply = NULL, version = version + 1 "
                        + "WHERE reply IS NOT NULL AND provider_id = ?",
                userId);
        int replyAudits = jdbcTemplate.update(
                "UPDATE reviews_aud SET reply = NULL WHERE reply IS NOT NULL AND provider_id = ?",
                userId);

        // The targeted cache invalidation (see the class javadoc): every
        // affected review id gets its "reviews" entry evicted AFTER this
        // transaction commits — the erased comment/reply texts are never
        // served from the cache to the public single-review read.
        for (UUID reviewId : affectedReviewIds) {
            eventPublisher.publishEvent(new CacheInvalidationRequested(REVIEWS_CACHE_NAMES, reviewId));
        }

        log.info("Reviews content purge: userId={}, comments={}, commentAuditRows={}, "
                        + "replies={}, replyAuditRows={}, cacheEvictions={}",
                userId, comments, commentAudits, replies, replyAudits, affectedReviewIds.size());
        return comments + commentAudits + replies + replyAudits;
    }
}
