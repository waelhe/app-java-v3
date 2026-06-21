package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Stores TOTP shared secret for MFA.
 * <p>Follows RFC 6238 — Time-Based One-Time Password Algorithm.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6238">RFC 6238</a>
 */
@Entity
@Table(name = "mfa_secrets")
@Audited
public class MfaSecret extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "secret", nullable = false, length = 64)
    private String secret;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    protected MfaSecret() {
    }

    private MfaSecret(UUID id, UUID userId, String secret) {
        this.id = id;
        this.userId = userId;
        this.secret = secret;
    }

    public static MfaSecret create(UUID userId, String secret) {
        return new MfaSecret(UUID.randomUUID(), userId, secret);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSecret() {
        return secret;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }
}
