package com.marketplace.notifications.routing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): storage for
 * the OPT-IN geographic subscriptions. Derived queries only; the
 * {@code @SoftDelete} filter of {@code BaseEntity} hides withdrawn rows
 * from every query here (the ACTIVE-visibility discipline of the
 * membership machinery), and {@link RevisionRepository} exposes the
 * Envers trail every subscription change leaves.
 */
public interface NotificationGeoSubscriptionRepository
        extends JpaRepository<NotificationGeoSubscription, UUID>,
        RevisionRepository<NotificationGeoSubscription, UUID, Integer> {

    /** The caller's own live subscriptions, the D-N5 order (newest first). */
    List<NotificationGeoSubscription> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId);

    /** The live-subscription existence check — the geo gate's opt-in arm. */
    boolean existsByUserIdAndLocationId(UUID userId, UUID locationId);

    /**
     * The urgent-alert fan-out's scan arm: every live subscriber of one
     * location (the V198 live-only partial index serves it).
     */
    List<NotificationGeoSubscription> findByLocationId(UUID locationId);

    /** The withdrawal's target lookup — the caller's own row only. */
    Optional<NotificationGeoSubscription> findByUserIdAndLocationId(UUID userId,
                                                                    UUID locationId);
}
