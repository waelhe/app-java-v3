package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): storage for
 * the hierarchical topic preference overrides — the V40 preferences
 * repository pattern verbatim. Derived queries only; {@link
 * RevisionRepository} exposes the Envers trail every switch change leaves.
 */
public interface NotificationTopicPreferenceRepository
        extends JpaRepository<NotificationTopicPreference, UUID>,
        RevisionRepository<NotificationTopicPreference, UUID, Integer> {

    /** All explicit topic overrides of one user — the effective matrix's basis. */
    List<NotificationTopicPreference> findByUserId(UUID userId);

    /**
     * The single override the routing engine's second resolution level
     * reads; empty means "no topic-level override — fall through".
     */
    Optional<NotificationTopicPreference> findByUserIdAndTopicAndChannel(UUID userId,
                                                                         NotificationTopic topic,
                                                                         NotificationChannel channel);
}
