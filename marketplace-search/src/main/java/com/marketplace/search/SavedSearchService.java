package com.marketplace.search;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.SavedSearchMatchedEvent;
import com.marketplace.shared.api.SearchCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): the /me
 * CRUD and the event-driven matcher.
 *
 * <p><b>The save-time type gate (criterion 5 — "بوابة النوع ذاتها"):</b>
 * the request's criteria node is materialized through the SAME
 * {@link SearchCriteria} canonical constructor the search surface binds
 * (Jackson runs the record's compact constructor): an invalid window,
 * non-positive guests/rooms/…, a partial radius triple — all answer 400
 * before any write. An unknown {@code locationId} answers 404 exactly
 * like the search surface ("never a silently-empty page") — the geo port
 * is consulted at save time so a stored search can never reference a
 * dead node.
 *
 * <p><b>Row 25 (agent findings 2026-10-25 — CWE-400): the coalescing
 * gate.</b> Before any population work, {@link #processListingActivated}
 * claims the scan for the listing: a non-blocking transactional advisory
 * lock (the TRY variant — a duplicate activation whose scan is already
 * in flight is consumed IMMEDIATELY, never parked on the shared event
 * listener thread) and the per-listing claim row — a listing whose last
 * scan is still inside the configured cooldown
 * ({@code marketplace.search.saved-searches.alert-scan-cooldown},
 * default 1h) coalesces the activation the same way. The pause-and-
 * reactivate replay the finding describes is thereby bounded to one
 * population scan per window per listing, while the scan itself — when
 * it runs — is byte-identical to the pre-row-25 walk (the faithfulness
 * rule is untouched: every scan evaluates the whole alert-enabled
 * population through the dispatch-faithful matcher). The claim stamp
 * commits atomically with the scan: a failed scan rolls the stamp back
 * and the registry's retry re-claims cleanly.
 *
 * <p><b>The matcher (criteria 1-4):</b> {@link #processListingActivated}
 * scans every alert-enabled saved search in deterministic KEYSET id batches,
 * delegates the membership question to {@link SavedSearchMatcher} (the
 * dispatch-faithful composition), inserts a ledger row per NEW match
 * with a native {@code INSERT ... ON CONFLICT DO NOTHING} (the skip is a
 * returned 0 — never a transaction-aborting 23505), and publishes ONE
 * {@link SavedSearchMatchedEvent} per (user, listing) carrying the list
 * of matched saved-search ids — the structural aggregation of criterion 4:
 * a hundred matching searches for one user are one notification. A
 * listener re-run on the same event inserts nothing new (the ledger skip)
 * and publishes nothing — the notification cannot double (criterion 3-b);
 * a delivery failure after publication is the registry's retry alone
 * (criterion 3 — the matching is never re-executed).
 *
 * <p><b>No logging on non-matches (criterion 2 — "لا إشعار ولا تسجيل"):</b>
 * the scan logs ONE structured line at the end (its counters), never a
 * line per non-matching search.
 */
@Service
public class SavedSearchService {

    private static final Logger log = LoggerFactory.getLogger(SavedSearchService.class);

    /** The scan's page size — bounded memory over an unbounded alert set. */
    private static final int SCAN_PAGE_SIZE = 200;

    /**
     * Row 25 (agent findings 2026-10-25 — CWE-400): the per-listing
     * in-flight gate — the TRY variant of the create path's advisory
     * TRANSACTION lock family ({@code lockPerUserCreate}). A waiting
     * {@code pg_advisory_xact_lock} would park the shared event listener
     * thread for the whole population scan (the thread-pool exhaustion
     * the finding's replay could then cause on its own); the try form
     * coalesces the duplicate IMMEDIATELY — the in-flight scan's
     * transaction already covers this activation's alerting purpose. The
     * {@code 'saved-search-scan:'} prefix namespaces the key away from the
     * create lock's raw user-id space, so the two advisory families can
     * never collide (domain separation by string, not by hash luck).
     * Auto-released at the surrounding transaction's commit or rollback.
     */
    static final String TRY_SCAN_LOCK =
            "SELECT pg_try_advisory_xact_lock(hashtextextended('saved-search-scan:' || ?::text, 0))";

    /**
     * Row 25: the claim read — the last ACTUAL scan's instant for the
     * listing (an empty list = never scanned). A PLAIN read, no
     * {@code FOR UPDATE}: the advisory lock above already serializes
     * scans per listing, so the row is uncontended while it is read.
     */
    static final String READ_SCAN_CLAIM =
            "SELECT last_scan_at FROM saved_search_scan_claims WHERE listing_id = ?";

    /**
     * Row 25: the claim stamp — unconditional for the same reason (the
     * advisory lock serializes; the row lock is never contended). Commits
     * WITH the scan: a failed scan rolls the stamp back and the event
     * registry's at-least-once retry re-claims from the pre-scan state —
     * the claim can never strand an activation behind a phantom stamp.
     */
    static final String STAMP_SCAN_CLAIM =
            "INSERT INTO saved_search_scan_claims (listing_id, last_scan_at) VALUES (?, ?) "
                    + "ON CONFLICT (listing_id) DO UPDATE SET last_scan_at = EXCLUDED.last_scan_at";

    private final SavedSearchRepository repository;
    private final SavedSearchMatcher matcher;
    private final GeoLookupPort geoLookupPort;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SearchProperties properties;
    private final EntityManager entityManager;

    public SavedSearchService(SavedSearchRepository repository,
                              SavedSearchMatcher matcher,
                              GeoLookupPort geoLookupPort,
                              ApplicationEventPublisher eventPublisher,
                              JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper,
                              Clock clock,
                              SearchProperties properties,
                              EntityManager entityManager) {
        this.repository = repository;
        this.matcher = matcher;
        this.geoLookupPort = geoLookupPort;
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        // constructor injection (not @PersistenceContext) so the unit
        // tests can mock the flush/clear contract — Spring injects the
        // shared transactional EntityManager either way.
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------
    // The /me CRUD
    // ------------------------------------------------------------------

    /**
     * Materializes + validates the criteria node through the record's own
     * gates, resolves the location against the geo tree (404 for an
     * unknown node — the search surface's own rule), and stores the
     * search. {@code alertEnabled=false} stores the search for later
     * re-use only — the scan skips it (criterion 6).
     */
    @Transactional
    public SavedSearch create(UUID userId, JsonNode criteriaNode, boolean alertEnabled) {
        SearchCriteria criteria = materializeCriteria(criteriaNode);
        if (criteria.locationId() != null
                && geoLookupPort.findSelfAndDescendants(criteria.locationId()).isEmpty()) {
            throw new ResourceNotFoundException("Unknown location: " + criteria.locationId());
        }
        // CodeRabbit round-1 adoption: the per-user availability bound —
        // the rate limiter bounds the write frequency; this bounds the
        // stored set the matcher scans on every listing activation.
        // CodeRabbit round-1 FOLLOW-UP (the cap thread's atomicity note):
        // the count-then-insert pair alone is a TOCTOU race — two
        // concurrent creates both read below the cap and both insert.
        // The advisory transaction lock below closes the window.
        lockPerUserCreate(userId);
        int cap = properties.savedSearches().maxPerUser();
        if (repository.countByUserId(userId) >= cap) {
            throw new ConflictException(
                    "Saved-search limit reached (" + cap + ") — delete one before saving another");
        }
        SavedSearch saved = SavedSearch.create(UUID.randomUUID(), userId, criteria, alertEnabled);
        return repository.save(saved);
    }

    @Transactional(readOnly = true)
    public Page<SavedSearch> listFor(UUID userId, Pageable pageable) {
        return repository.findByOwner(userId, pageable);
    }

    /** Owner-scoped soft delete — a foreign id is an honest 404 (the /me convention). */
    @Transactional
    public void delete(UUID userId, UUID savedSearchId) {
        SavedSearch saved = repository.findById(savedSearchId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Saved search not found: " + savedSearchId));
        repository.delete(saved);
    }

    // ------------------------------------------------------------------
    // The event-driven matcher
    // ------------------------------------------------------------------

    /**
     * The {@code ListingActivatedEvent} handler body. Runs inside the
     * listener's own transaction (REQUIRES_NEW): the ledger inserts, the
     * {@code last_matched_at} stamps and the published events commit
     * atomically — a crash rolls the whole unit back and the registry
     * retries the event cleanly.
     *
     * <p><b>Row 25 (CWE-400): the coalescing gate runs FIRST.</b> A
     * listing whose scan is already in flight, or whose last scan is
     * still inside the activation budget
     * ({@code marketplace.search.saved-searches.alert-scan-cooldown}),
     * consumes the activation without any population work: ONE info
     * line, no batches, no matcher calls, no ledger writes. The claim
     * stamp that a RUNNING scan writes commits atomically with it (see
     * {@link #STAMP_SCAN_CLAIM}).
     */
    @Transactional
    public void processListingActivated(UUID listingId, UUID providerId) {
        // Row 25: the budget claim precedes the population work — the
        // pause/reactivate replay dies here, before a single batch.
        if (!beginScanWindow(listingId)) {
            log.info("Saved-search scan coalesced for listing {}: the last scan is still inside the activation budget",
                    listingId);
            return;
        }
        Map<UUID, List<UUID>> newMatchesPerUser = new HashMap<>();
        int scanned = 0;
        int matched = 0;
        Instant now = clock.instant();

        // CodeRabbit round-1 adoptions: KEYSET pagination (an offset window
        // recalculation silently skips a saved search when a concurrent
        // soft delete shifts rows left — a missed alert, not a retry) and a
        // FLUSH+CLEAR persistence context per batch (the scan's page size
        // must bound memory, not just the query).
        UUID lastSeenId = null;
        Slice<SavedSearch> slice;
        do {
            slice = lastSeenId == null
                    ? repository.findFirstAlertEnabledBatch(PageRequest.of(0, SCAN_PAGE_SIZE))
                    : repository.findAlertEnabledAfter(lastSeenId, PageRequest.of(0, SCAN_PAGE_SIZE));
            for (SavedSearch saved : slice.getContent()) {
                scanned++;
                lastSeenId = saved.getId();
                if (!matcher.matches(saved.getCriteria(), listingId, providerId)) {
                    continue;
                }
                matched++;
                if (insertLedgerRow(saved.getId(), listingId, now) == 0) {
                    continue; // the documented skip — this pair was already reported
                }
                saved.markMatched(now);
                newMatchesPerUser.computeIfAbsent(saved.getUserId(), k -> new ArrayList<>())
                        .add(saved.getId());
            }
            entityManager.flush();
            entityManager.clear();
        } while (slice.hasNext());

        // The structural aggregation (criterion 4): one event per user —
        // the notification layer cannot spam even if it wanted to.
        newMatchesPerUser.forEach((userId, savedSearchIds) ->
                eventPublisher.publishEvent(new SavedSearchMatchedEvent(userId, listingId, savedSearchIds)));

        // ONE structured line for the whole scan — never a line per
        // non-match (criterion 2).
        log.info("Saved-search scan: listingId={}, scanned={}, matched={}, notifiedUsers={}",
                listingId, scanned, matched, newMatchesPerUser.size());
    }

    /**
     * Row 25 (CWE-400): the per-listing scan claim — the coalescing and
     * activation budget the finding asked for, in one gate.
     *
     * <p><b>In-flight coalescing (non-blocking):</b> the transactional
     * advisory TRY lock — a scan already running for THIS listing makes
     * the claim fail fast (the listener thread is never parked on a row
     * lock held for a whole population scan; the waiting variant of the
     * same lock is exactly that trap).
     *
     * <p><b>The time budget:</b> the claim row's {@code last_scan_at} —
     * a last scan inside the configured cooldown coalesces the
     * activation (the budget anchors to the last ACTUAL scan; a
     * coalesced activation never re-stamps it). {@code ZERO} cooldown
     * disables the time budget on purpose (only the in-flight gate
     * remains).
     *
     * <p>The stamp is written INSIDE this transaction, so it commits or
     * rolls back WITH the scan it gates — the at-least-once registry
     * contract survives a failed scan unchanged.
     *
     * @return {@code true} when the scan may run (the claim is taken);
     *         {@code false} when the activation is coalesced.
     */
    private boolean beginScanWindow(UUID listingId) {
        List<Boolean> inFlight = jdbcTemplate.queryForList(TRY_SCAN_LOCK, Boolean.class, listingId.toString());
        if (inFlight.isEmpty() || !Boolean.TRUE.equals(inFlight.get(0))) {
            return false; // a scan for this listing is already in flight
        }
        Instant now = clock.instant();
        List<java.sql.Timestamp> lastScan =
                jdbcTemplate.queryForList(READ_SCAN_CLAIM, java.sql.Timestamp.class, listingId);
        Duration cooldown = properties.savedSearches().alertScanCooldown();
        if (!lastScan.isEmpty()
                && lastScan.get(0).toInstant().plus(cooldown).isAfter(now)) {
            return false; // still inside the activation budget
        }
        jdbcTemplate.update(STAMP_SCAN_CLAIM, listingId, java.sql.Timestamp.from(now));
        return true;
    }

    /**
     * The create unit's per-user serialization (the CodeRabbit round-1
     * follow-up on the cap thread): a PostgreSQL advisory TRANSACTION
     * lock — auto-released at commit or rollback, so there is no unlock
     * path to forget — keyed by the hash of the user id. The second
     * concurrent create waits for the first unit to commit, re-reads the
     * count, and answers 409 at the cap. A hash collision between two
     * different users only over-serializes those two units for the
     * microseconds of the insert; it never touches the matcher's scan
     * (the lock lives inside the create transaction only — single
     * advisory lock per transaction, no lock-ordering inversion).
     */
    private void lockPerUserCreate(UUID userId) {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            try (var ps = con.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))")) {
                ps.setString(1, userId.toString());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                }
            }
            return null;
        });
    }

    /**
     * The idempotency ledger insert — native {@code INSERT ... ON CONFLICT
     * DO NOTHING} (the V52 seeding precedent): the skip is the affected-
     * row count, so a re-run of the listener on the same event is a
     * quiet no-op instead of a transaction-aborting constraint violation.
     */
    private int insertLedgerRow(UUID savedSearchId, UUID listingId, Instant matchedAt) {
        return jdbcTemplate.update(
                "INSERT INTO saved_search_matches (id, saved_search_id, listing_id, matched_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), savedSearchId, listingId, java.sql.Timestamp.from(matchedAt));
    }

    /**
     * The save-time gate: the criteria node through the record's canonical
     * constructor (Jackson) — a {@link BadRequestException} (the record's
     * own, possibly wrapped by the binding layer in a
     * {@code ValueInstantiationException}) propagates as the house 400; any
     * other Jackson binding failure (an unknown enum name, a malformed
     * number) is mapped to the same 400 contract rather than Spring's bare
     * unreadable-body default.
     */
    private SearchCriteria materializeCriteria(JsonNode criteriaNode) {
        if (criteriaNode == null || !criteriaNode.isObject()) {
            throw new BadRequestException("criteria must be a JSON object");
        }
        try {
            return objectMapper.treeToValue(criteriaNode, SearchCriteria.class);
        } catch (BadRequestException gate) {
            throw gate; // the record's own type gate — the house 400
        } catch (JacksonException binding) {
            // The record's compact-constructor gate may surface wrapped —
            // unwrap it so the gate's own message answers the client.
            for (Throwable cause = binding; cause != null; cause = cause.getCause()) {
                if (cause instanceof BadRequestException gate) {
                    throw gate;
                }
            }
            throw new BadRequestException("Invalid search criteria: " + binding.getOriginalMessage());
        }
    }
}
