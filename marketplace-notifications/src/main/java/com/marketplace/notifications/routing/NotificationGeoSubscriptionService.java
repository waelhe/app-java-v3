package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationGeoSubscriptionView;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
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

    /** The level the subscription targets — the neighborhood scope (L42's). */
    private static final int NEIGHBORHOOD_LEVEL = 3;

    private final NotificationGeoSubscriptionRepository repository;
    private final GeoLookupPort geoLookupPort;
    private final CurrentUserProvider currentUserProvider;

    public NotificationGeoSubscriptionService(NotificationGeoSubscriptionRepository repository,
                                              GeoLookupPort geoLookupPort,
                                              CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.geoLookupPort = geoLookupPort;
        this.currentUserProvider = currentUserProvider;
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
     * the live pair already answers, the same view returns unchanged.
     */
    @Observed(name = "notification.preferences.geo.subscribe")
    public NotificationGeoSubscriptionView subscribe(Authentication authentication,
                                                     UUID locationId) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        requireNeighborhood(locationId);
        if (repository.existsByUserIdAndLocationId(userId, locationId)) {
            return repository.findByUserIdAndLocationId(userId, locationId)
                    .map(this::toView)
                    .orElseThrow(() -> new IllegalStateException(
                            "Live subscription vanished between the check and the read: "
                                    + userId + " / " + locationId));
        }
        NotificationGeoSubscription saved = repository.saveAndFlush(
                NotificationGeoSubscription.subscribe(userId, locationId));
        return toView(saved);
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
}
