package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.FollowedProviderNewListingEvent;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * W4 (yelp-level plan §5 — the reviewer identity &amp; engagement wave, G21):
 * the follow domain — the /me CRUD and the activation bridge.
 *
 * <p><b>The write path (the {@code createOrganic} W1 gate order, measured):</b>
 * the provider PROFILE id is resolved through {@link ProviderLookupPort}
 * (404 for an unknown profile — never a silently-empty page), the self-follow
 * pair answers 400 (the "not the provider itself" precedent — a member never
 * follows his own provider profile), a live duplicate answers the explicit
 * 409, and V93's partial unique index is the concurrent-insert backstop.
 * The stored {@code provider_user_id} is the USER id (A1) — the id
 * {@code ListingActivatedEvent} carries, so the bridge joins directly.
 *
 * <p><b>The activation bridge (the saved-search matcher's own shape, the
 * same event):</b> {@link #onListingActivated} scans every live follow of
 * the listing's provider, inserts one alert-ledger row per (follower,
 * listing) pair with a native {@code INSERT ... ON CONFLICT DO NOTHING} (the
 * {@code saved_search_matches} V54 precedent — the skip is a returned 0,
 * never a transaction-aborting 23505), and publishes ONE
 * {@link FollowedProviderNewListingEvent} per NEW pair. The ledger is the
 * "تنبيهًا واحدًا" guarantee made structural: a registry re-delivery of the
 * same activation (the at-least-once D-E10 semantics) inserts nothing and
 * publishes nothing, so a follower is told about a listing announcement
 * exactly once — ever — regardless of retries.
 *
 * <p>The bridge unit runs inside the listener's own transaction
 * (REQUIRES_NEW): the ledger inserts and the published events commit
 * atomically — a crash rolls the whole unit back and the registry retries
 * the event cleanly (the {@code processListingActivated} contract).
 */
@Service
@Transactional
public class ProviderFollowService {

    private static final Logger log = LoggerFactory.getLogger(ProviderFollowService.class);

    private final ProviderFollowRepository repository;
    private final ProviderLookupPort providerLookupPort;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    public ProviderFollowService(ProviderFollowRepository repository,
                                 ProviderLookupPort providerLookupPort,
                                 ApplicationEventPublisher eventPublisher,
                                 JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.providerLookupPort = providerLookupPort;
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------
    // The /me CRUD — the service composes the DTO views (the
    // controllersMustNotDependOnJpaEntities gate: the HTTP boundary speaks
    // the ProviderFollowView record only; the entity never crosses it)
    // ------------------------------------------------------------------

    /**
     * Follow a provider. The request's provider id is the PUBLIC PAGE's key
     * (the profile id — the same client-facing convention the organic
     * review's {@code providerId} pins); storage keeps the USER id. The
     * answer is the composed view (the summary the resolution already
     * fetched — no second lookup on the write path).
     */
    @Observed(name = "provider.follow.create")
    public ProviderFollowView create(UUID userId, UUID providerProfileId) {
        ProviderSummary provider = providerLookupPort.findById(providerProfileId)
                .filter(summary -> summary.userId() != null)
                .orElseThrow(() -> new ResourceNotFoundException("Provider not found: " + providerProfileId));
        UUID providerUserId = provider.userId();

        if (providerUserId.equals(userId)) {
            throw new BadRequestException("You cannot follow your own provider profile");
        }
        if (repository.existsByUserIdAndProviderUserId(userId, providerUserId)) {
            throw new ConflictException("You already follow this provider");
        }
        ProviderFollow saved = repository.save(
                ProviderFollow.create(UUID.randomUUID(), userId, providerUserId));
        return ProviderFollowView.of(saved, provider);
    }

    /**
     * The member's "my follows" page — the follows composed with the
     * followed providers' current public identity in ONE batch provider
     * resolution for the whole page (the W1 {@code findAllByIds} N+1 rule),
     * never a lookup per row; an empty page costs no query at all.
     */
    @Transactional(readOnly = true)
    public Page<ProviderFollowView> listFor(UUID userId, Pageable pageable) {
        Page<ProviderFollow> page = repository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        Map<UUID, ProviderSummary> providers = providersOf(page);
        return page.map(follow -> ProviderFollowView.of(follow, providers.get(follow.getProviderUserId())));
    }

    /**
     * The list's batch name resolution — one {@code findAllByUserIds} for
     * the page's distinct followed providers, keyed by the stored USER id
     * (the row's own key, so the composition is a direct map lookup).
     */
    private Map<UUID, ProviderSummary> providersOf(Page<ProviderFollow> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        return providerLookupPort.findAllByUserIds(
                page.getContent().stream().map(ProviderFollow::getProviderUserId).collect(Collectors.toSet()));
    }

    /** Owner-scoped soft delete — a foreign id is an honest 404 (the /me convention). */
    @Observed(name = "provider.follow.delete")
    public void delete(UUID userId, UUID followId) {
        ProviderFollow follow = repository.findByIdAndUserId(followId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Follow not found: " + followId));
        repository.delete(follow);
    }

    // ------------------------------------------------------------------
    // The activation bridge
    // ------------------------------------------------------------------

    /**
     * The {@code ListingActivatedEvent} handler body — returns the number
     * of followers genuinely alerted (the log line's counter, never a line
     * per follower).
     */
    @Transactional
    public int onListingActivated(UUID listingId, UUID providerUserId) {
        int alerted = 0;
        for (ProviderFollow follow : repository.findByProviderUserId(providerUserId)) {
            // The self-follow row cannot exist (the create gate), so no
            // publisher-exclusion branch here — unlike the community
            // bridge, whose memberships CAN include the listing's own
            // provider. Every row this loop sees is a genuine follower.
            if (insertAlertLedgerRow(follow.getUserId(), listingId) == 0) {
                continue; // the documented skip — this (follower, listing) pair was already alerted
            }
            eventPublisher.publishEvent(
                    new FollowedProviderNewListingEvent(follow.getUserId(), listingId));
            alerted++;
        }
        // ONE structured line for the whole bridge — never a line per
        // follower (the saved-search scan's criterion-2 discipline).
        log.info("Provider-follow bridge: listingId={}, providerUserId={}, followersAlerted={}",
                listingId, providerUserId, alerted);
        return alerted;
    }

    /**
     * The idempotency ledger insert — native {@code INSERT ... ON CONFLICT
     * DO NOTHING} (the V54 {@code saved_search_matches} precedent): the
     * skip is the affected-row count, so a re-run of the bridge on a
     * re-delivered activation is a quiet no-op instead of a
     * transaction-aborting constraint violation.
     */
    private int insertAlertLedgerRow(UUID followerId, UUID listingId) {
        return jdbcTemplate.update(
                "INSERT INTO provider_follow_alerts (id, user_id, listing_id, alerted_at) "
                        + "VALUES (?, ?, ?, now()) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), followerId, listingId);
    }
}
