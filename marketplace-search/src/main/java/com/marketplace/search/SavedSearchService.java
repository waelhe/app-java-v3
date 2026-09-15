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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.Clock;
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
     */
    @Transactional
    public void processListingActivated(UUID listingId, UUID providerId) {
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
