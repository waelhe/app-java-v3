package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationGeoSubscriptionView;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * OPT-IN geographic subscription domain — the self-service seat of the
 * routing engine's geo dimension.
 *
 * <p><b>The documented assumptions (§8.1), enforced here:</b>
 * <ul>
 *   <li>OPT-IN ONLY — subscriptions are born only from this service's
 *       own subscribe path (the user's explicit act); nothing infers a
 *       scope and no default row exists.</li>
 *   <li>THE DEFAULT IS THE MEMBERSHIP — the engine's effective geo scope
 *       with zero subscription rows is the user's ACTIVE membership
 *       neighborhood (the {@code CommunityMembershipPort} answer); these
 *       rows only ever WIDEN it, one level-3 neighborhood at a time.</li>
 *   <li>WITHDRAWAL IS EXPLICIT AND REVERSIBLE — the withdraw path
 *       soft-deletes the caller's own row; the V198 partial unique index
 *       keys live rows only, so the pair can be re-subscribed later.</li>
 * </ul>
 *
 * <p><b>The write gate (the realestate L31 location gate verbatim):</b>
 * the target location must EXIST ({@code GeoLookupPort.getLocation} —
 * unknown id is a 404, never a silently-accepted value) and must be
 * LEVEL 3 (a neighborhood — the scope the whole neighborhood surface
 * speaks; a wider level would widen the fan-out beyond the
 * neighborhood-level contract). Every read and write is SELF-scoped —
 * the recipient-isolation rule of the Phase 7 gate (a user reaches only
 * their own subscriptions, never another user's).
 */
@Service
@Transactional
public class NotificationGeoSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(NotificationGeoSubscriptionService.class);

    /** The level the subscription targets — the neighborhood scope (L42's). */
    private static final int NEIGHBORHOOD_LEVEL = 3;

    private final NotificationGeoSubscriptionRepository repository;
    private final GeoLookupPort geoLookupPort;
    private final CurrentUserProvider currentUserProvider;
    private final NotificationGeoSubscriptionWriter writer;

    public NotificationGeoSubscriptionService(NotificationGeoSubscriptionRepository repository,
                                              GeoLookupPort geoLookupPort,
                                              CurrentUserProvider currentUserProvider,
                                              NotificationGeoSubscriptionWriter writer) {
        this.repository = repository;
        this.geoLookupPort = geoLookupPort;
        this.currentUserProvider = currentUserProvider;
        this.writer = writer;
    }

    /** The caller's own live subscriptions (D-N5 order, newest first). */
    @Transactional(readOnly = true)
    public List<NotificationGeoSubscriptionView> getMySubscriptions(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return repository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * Subscribes the caller to one level-3 neighborhood — the explicit
     * geographic act (AC-02-02). Duplicate subscription is idempotent:
     * the live pair already answers, the same view returns unchanged —
     * including the in-flight race where two concurrent same-pair
     * requests both miss the lookup (CodeRabbit's round on the #545
     * slice, the FollowWriter house pattern): the INSERT rides its own
     * REQUIRES_NEW transaction, the loser absorbs the
     * {@code uq_notification_geo_subscriptions_live} violation OUTSIDE
     * the transaction that took it and re-reads the winner's committed
     * row — the documented idempotent 200, never a rollback-only 500.
     * Every OTHER integrity violation propagates honestly.
     */
    @Observed(name = "notification.preferences.geo.subscribe")
    public NotificationGeoSubscriptionView subscribe(Authentication authentication,
                                                     UUID locationId) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        requireNeighborhood(locationId);
        NotificationGeoSubscription existing = repository
                .findByUserIdAndLocationId(userId, locationId).orElse(null);
        if (existing != null) {
            return toView(existing);
        }
        try {
            NotificationGeoSubscription saved = writer.persist(
                    NotificationGeoSubscription.subscribe(userId, locationId));
            return toView(saved);
        } catch (DataIntegrityViolationException duplicate) {
            if (!isOneLiveSubscriptionDuplicate(duplicate)) {
                throw duplicate;
            }
            log.info("Concurrent geo subscription lost the create race — answering the "
                    + "winner's row: userId={}, locationId={}", userId, locationId);
            return toView(writer.replay(userId, locationId));
        }
    }

    /**
     * Withdraws the caller's own subscription — the explicit reversal.
     * Unknown pair is a 404 (the honest answer: nothing to withdraw).
     */
    @Observed(name = "notification.preferences.geo.withdraw")
    public void withdraw(Authentication authentication, UUID locationId) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        NotificationGeoSubscription subscription =
                repository.findByUserIdAndLocationId(userId, locationId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "No subscription to withdraw: location " + locationId));
        repository.delete(subscription);
    }

    /** The L31 gate verbatim + the level-3 scope gate (the D-N2 hierarchy). */
    private void requireNeighborhood(UUID locationId) {
        GeoLookupPort.GeoNode node = geoLookupPort.getLocation(locationId);
        if (node.level() != NEIGHBORHOOD_LEVEL) {
            throw new BadRequestException(
                    "Geo subscriptions target level-3 neighborhoods only (got level "
                            + node.level() + ")");
        }
    }

    private NotificationGeoSubscriptionView toView(NotificationGeoSubscription subscription) {
        return new NotificationGeoSubscriptionView(
                subscription.getLocationId(),
                subscription.subscribedAt());
    }

    /**
     * The one-live-subscription duplicate test: the violation's cause
     * chain must name {@code uq_notification_geo_subscriptions_live}
     * itself (V198) — every OTHER integrity violation is an honest
     * failure that must propagate (the FollowService twin verbatim).
     */
    private static boolean isOneLiveSubscriptionDuplicate(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null
                    && t.getMessage().contains("uq_notification_geo_subscriptions_live")) {
                return true;
            }
        }
        return false;
    }
}
