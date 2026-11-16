package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * A-04 (official-compliance plan §6, wave A — A.1/A.2): a single-use,
 * time-limited auth action token. The V112 {@code auth_action_tokens} row
 * — the store's shape is documented in the migration itself (the FK to the
 * V13 login store, the live-token partial unique index, the purpose
 * membership CHECK, the BaseEntity column set).
 *
 * <p><b>Single use is enforced TWICE, by design (not check-then-act
 * luck):</b> the partial unique index holds ONE live row per (account,
 * purpose) at the store level, and this entity's inherited {@code @Version}
 * (BaseEntity) makes redemption an optimistic-lock critical section — two
 * concurrent redemptions both load {@code consumed_at IS NULL}, the first
 * UPDATE wins the version, the second fails with the optimistic lock
 * exception and the caller maps it to the honest 400. The OWASP Forgot
 * Password Cheat Sheet (the declared trusted community source) is the
 * measured authority for the shape: "Single use and expire after an
 * appropriate period", tokens "Randomly generated using a
 * cryptographically safe algorithm".</p>
 *
 * <p><b>The digest, never the secret:</b> {@code tokenHash} is the SHA-256
 * hex of the raw token — the raw value exists only in the outbound mail
 * and the transient event payload (see {@code PasswordResetRequestedEvent}
 * for the measured registry-lifecycle bound on that copy). A store snapshot
 * therefore never carries a redeemable credential.</p>
 *
 * <p>{@code @Audited} (the AGENTS.md Envers rule): the consumption of a
 * security artifact is exactly the kind of fact the audit trail exists
 * for — WHICH transaction consumed a token (a user redemption, an
 * administrative disable, a pseudonymization) is distinguishable after the
 * fact through the revision, even though the row itself only carries
 * {@code consumed_at}.</p>
 */
@Entity
@Table(name = "auth_action_tokens")
@Audited
public class AuthActionToken extends BaseEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    /** The login account this token redeems for — the V13 store's own domain. */
    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private AuthActionTokenPurpose purpose;

    /** SHA-256 hex of the raw token — 64 hex chars, unique (V112 index). */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Null while live; the single-use stamp set exactly once at redemption or invalidation. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected AuthActionToken() {}

    private AuthActionToken(UUID id, String username, AuthActionTokenPurpose purpose,
                            String tokenHash, Instant expiresAt) {
        this.id = id;
        this.username = username;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    static AuthActionToken issue(String username, AuthActionTokenPurpose purpose,
                                 String tokenHash, Instant expiresAt) {
        return new AuthActionToken(UUID.randomUUID(), username, purpose, tokenHash, expiresAt);
    }

    void consume(Instant now) {
        this.consumedAt = now;
    }

    boolean isConsumed() {
        return consumedAt != null;
    }

    boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getUsername() {
        return username;
    }

    AuthActionTokenPurpose getPurpose() {
        return purpose;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getConsumedAt() {
        return consumedAt;
    }

    /** The stored digest — package-private: the unit guards' hash-at-rest read seam. */
    String getTokenHash() {
        return tokenHash;
    }
}
