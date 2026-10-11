package com.marketplace.notifications;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Stage 7 (plan D-10, ADR-0003): one device's push token — the registry
 * row the FCM channel addresses. The token is UNIQUE (one hardware token,
 * one row; the register call is the upsert), the platform is the V173
 * CHECK's membership set, and {@code user_id} is a plain UUID column —
 * the V40 discipline verbatim (the notifications module resolves users
 * through its lookup seams, never a database-level identity dependency).
 * Audited per the AGENTS.md rule (the V24 mirror in V173).
 */
@Entity
@Table(name = "push_tokens")
@Audited
public class PushToken extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The provider's device token (the register call's idempotency key). */
    @Column(name = "token", nullable = false, unique = true, columnDefinition = "TEXT")
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    private Platform platform;

    public enum Platform {
        ANDROID,
        IOS,
        WEB
    }

    protected PushToken() {
        // JPA
    }

    private PushToken(UUID id, UUID userId, String token, Platform platform) {
        this.id = id;
        this.userId = userId;
        this.token = token;
        this.platform = platform;
    }

    /** The register write — same user + same token = the SAME row (the upsert). */
    public static PushToken register(UUID userId, String token, Platform platform) {
        return new PushToken(UUID.randomUUID(), userId, token, platform);
    }

    /** Re-binds the token to the caller on re-registration (the reinstall path). */
    public void rebindTo(UUID userId, Platform platform) {
        this.userId = userId;
        this.platform = platform;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public Platform getPlatform() {
        return platform;
    }
}
