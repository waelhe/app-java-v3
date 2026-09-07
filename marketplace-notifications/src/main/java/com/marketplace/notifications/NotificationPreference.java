package com.marketplace.notifications;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * L22 (feature-expansion roadmap §5, Week 2): one channel switch for one
 * user and one notification type — the per-channel unsubscribe record.
 *
 * <p><b>Sparse-override semantics:</b> the table stores only explicit
 * overrides. The absence of a row IS the default, and the default is
 * {@code enabled = true} — so before any preference is written the system
 * behaves exactly as it did pre-L22 (roadmap acceptance criterion 2).
 *
 * <p>{@code user_id} is a plain UUID column with no FK to {@code users},
 * following the module-decoupling convention of {@code notifications.recipient_id}
 * (V18) and {@code media_assets} (V32): the notifications module resolves
 * users through {@code UserLookupPort}, never through a database-level
 * dependency on the identity module's table.
 *
 * <p>Every switch change leaves an Envers revision (roadmap acceptance
 * criterion 3): the row is {@link Audited}, mirroring the {@code @Audited}
 * convention of every owned entity (AGENTS.md).
 */
@Entity
@Table(name = "notification_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notification_preferences_user_type_channel",
                columnNames = {"user_id", "type", "channel"}))
@Audited
public class NotificationPreference extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The notification type this switch governs (V40 CHECK-constrained). */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 100)
    private NotificationType type;

    /** The delivery channel this switch governs (V40 CHECK-constrained). */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected NotificationPreference() {
    }

    private NotificationPreference(UUID id, UUID userId, NotificationType type,
                                   NotificationChannel channel, boolean enabled) {
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.channel = channel;
        this.enabled = enabled;
    }

    /**
     * Creates an explicit override row (the upsert "insert" arm of
     * {@code NotificationPreferenceService}).
     *
     * @param userId   the preference's owner (users.id space)
     * @param type     the notification type the switch governs
     * @param channel  the delivery channel the switch governs
     * @param enabled  whether the channel stays on for that type
     * @return the new, unsaved preference row
     */
    public static NotificationPreference create(UUID userId, NotificationType type,
                                                NotificationChannel channel, boolean enabled) {
        return new NotificationPreference(UUID.randomUUID(), userId, type, channel, enabled);
    }

    /**
     * Flips the switch (the upsert "update" arm) — an Envers MOD revision
     * records every change.
     *
     * @param enabled the new channel state
     */
    public void change(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
