package com.marketplace.notifications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * L22 (feature-expansion roadmap §5, Week 2): storage for the per-channel
 * notification preference overrides. Derived queries only — the sparse
 * lookup ({@link #findByUserIdAndTypeAndChannel}) is the send gate's single
 * read; {@link RevisionRepository} exposes the Envers trail every switch
 * change leaves (acceptance criterion 3).
 */
public interface NotificationPreferenceRepository
        extends JpaRepository<NotificationPreference, UUID>,
        RevisionRepository<NotificationPreference, UUID, Integer> {

    /**
     * All explicit overrides of one user — the basis of the effective
     * preference matrix the GET endpoint returns.
     */
    List<NotificationPreference> findByUserId(UUID userId);

    /**
     * The single override the send gate reads before delivering on a
     * channel; empty means "no override — default enabled".
     */
    Optional<NotificationPreference> findByUserIdAndTypeAndChannel(UUID userId,
                                                                   NotificationType type,
                                                                   NotificationChannel channel);
}
