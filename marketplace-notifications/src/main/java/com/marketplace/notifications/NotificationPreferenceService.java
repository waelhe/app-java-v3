package com.marketplace.notifications;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * L22 (feature-expansion roadmap §5, Week 2): the notification preference
 * domain — the send gate every delivery consults and the self-service
 * matrix the endpoints expose. The whole feature lives inside this module
 * (the roadmap's integration point: "internal preference read").
 *
 * <p><b>The sparse-override model:</b> only explicit overrides are stored.
 * {@link #isChannelEnabled} returns {@code true} for a user×type×channel
 * with no row — the default IS the pre-L22 behavior, so the system's
 * default state is byte-for-byte the old one (acceptance criterion 2).
 *
 * <p><b>Channel semantics (the L22 scope):</b> the in-app (DB) channel is
 * always on — the roadmap: "inside the app always"; EMAIL is gated before
 * every send; WS is sent by default and honors an explicit opt-out. The
 * DB switch is still stored so future channels (roadmap §7) are single
 * addition points.
 */
@Service
@Transactional
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;
    private final CurrentUserProvider currentUserProvider;

    public NotificationPreferenceService(NotificationPreferenceRepository repository,
                                         CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * The L22 send gate: is this channel enabled for this user and
     * notification type? Absence of an override row means enabled (the
     * default) — so with no preferences written, every send proceeds
     * exactly as it did pre-L22.
     *
     * @param userId   the recipient (users.id space)
     * @param type     the notification type being delivered
     * @param channel  the delivery channel being consulted
     * @return false only when an explicit override disables the channel
     */
    @Transactional(readOnly = true)
    public boolean isChannelEnabled(UUID userId, NotificationType type, NotificationChannel channel) {
        return repository.findByUserIdAndTypeAndChannel(userId, type, channel)
                .map(NotificationPreference::isEnabled)
                .orElse(true);
    }

    /**
     * The effective matrix the GET endpoint returns: every notification
     * type × every channel, each with its stored override or the enabled
     * default — six rows today (2 types × 3 channels).
     *
     * @param authentication the caller (self-scoped read)
     * @return the full effective matrix in stable (type, channel) order
     */
    @Transactional(readOnly = true)
    public List<NotificationPreferenceView> getMyPreferences(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return effectiveMatrix(userId);
    }

    /**
     * The PUT path: applies every switch in the request (upsert — create
     * the override row if absent, flip the existing one otherwise) and
     * returns the resulting effective matrix. A request naming the same
     * type×channel twice is rejected up front: two different values for
     * one switch is a client bug, and silent last-write-wins would hide it.
     *
     * @param authentication the caller (self-scoped write)
     * @param request        the switches to apply
     * @return the full effective matrix after the application
     */
    @Observed(name = "notification.preferences.update")
    public List<NotificationPreferenceView> updateMyPreferences(Authentication authentication,
                                                                NotificationPreferencesUpdateRequest request) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        Set<PreferenceKey> seen = new HashSet<>();
        for (NotificationPreferenceUpdate update : request.preferences()) {
            PreferenceKey key = new PreferenceKey(update.type(), update.channel());
            if (!seen.add(key)) {
                throw new BadRequestException(
                        "Duplicate preference entry for type=" + key.type() + ", channel=" + key.channel());
            }
        }
        for (NotificationPreferenceUpdate update : request.preferences()) {
            repository.findByUserIdAndTypeAndChannel(userId, update.type(), update.channel())
                    .ifPresentOrElse(
                            existing -> existing.change(update.enabled()),
                            () -> repository.save(NotificationPreference.create(
                                    userId, update.type(), update.channel(), update.enabled())));
        }
        return effectiveMatrix(userId);
    }

    /**
     * Builds the full effective matrix for one user: every notification
     * type × every channel with the stored override or the enabled
     * default, in stable (type, channel) order — six rows today.
     */
    private List<NotificationPreferenceView> effectiveMatrix(UUID userId) {
        Map<PreferenceKey, Boolean> stored = repository.findByUserId(userId).stream()
                .collect(Collectors.toMap(p -> new PreferenceKey(p.getType(), p.getChannel()),
                        NotificationPreference::isEnabled));
        List<NotificationPreferenceView> matrix = new ArrayList<>();
        for (NotificationType type : NotificationType.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                matrix.add(new NotificationPreferenceView(type, channel,
                        stored.getOrDefault(new PreferenceKey(type, channel), true)));
            }
        }
        return matrix;
    }

    /** The composite key of the sparse override lookup. */
    private record PreferenceKey(NotificationType type, NotificationChannel channel) {
    }
}
