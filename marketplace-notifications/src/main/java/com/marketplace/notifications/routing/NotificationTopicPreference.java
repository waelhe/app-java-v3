package com.marketplace.notifications.routing;

import com.marketplace.notifications.NotificationChannel;
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
 * Phase 7 (execution plan §10 / §8.1 — notification routing): one
 * hierarchical TOPIC switch for one user and one opt-outable channel —
 * the V198 {@code notification_topic_preferences} row, the second
 * resolution level of the preference hierarchy (the type-level row of
 * V40 {@code notification_preferences} overrides this row, which
 * overrides the enabled default).
 *
 * <p>The sparse-override semantics are the V40 model verbatim: only
 * explicit overrides are stored, the absence of a row IS the enabled
 * default, "back to default" is {@code enabled = true}.
 *
 * <p>Channels are EMAIL and WS only (the V198 CHECK pins it): the in-app
 * (DB) channel is always on (the L22 standing rule) and PUSH has no
 * stored preference until the provider decision (D-10) lands — a
 * channel outside the pair cannot be stored, so the API never reports a
 * state the delivery path does not honor.
 *
 * <p>{@code user_id} is a plain UUID column with no FK to users — the
 * module-decoupling convention of the V40 preferences table verbatim.
 * Every switch change leaves an Envers revision ({@code @Audited}).
 */
@Entity
@Table(name = "notification_topic_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_notification_topic_preferences_user_topic_channel",
                columnNames = {"user_id", "topic", "channel"}))
@Audited
public class NotificationTopicPreference extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The subject family this switch governs (the V198 CHECK-constrained). */
    @Enumerated(EnumType.STRING)
    @Column(name = "topic", nullable = false, length = 30)
    private NotificationTopic topic;

    /** The opt-outable channel this switch governs (EMAIL/WS only — V198). */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected NotificationTopicPreference() {
    }

    private NotificationTopicPreference(UUID id, UUID userId, NotificationTopic topic,
                                        NotificationChannel channel, boolean enabled) {
        this.id = id;
        this.userId = userId;
        this.topic = topic;
        this.channel = channel;
        this.enabled = enabled;
    }

    /**
     * Creates an explicit override row (the upsert "insert" arm of
     * {@link NotificationTopicPreferenceService}).
     */
    public static NotificationTopicPreference create(UUID userId, NotificationTopic topic,
                                                     NotificationChannel channel,
                                                     boolean enabled) {
        return new NotificationTopicPreference(UUID.randomUUID(), userId, topic, channel,
                enabled);
    }

    /** Flips the switch (the upsert "update" arm) — an Envers MOD revision. */
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

    public NotificationTopic getTopic() {
        return topic;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
