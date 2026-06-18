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
 * Entity for email verification and password reset tokens.
 * <p>Follows OWASP Authentication Cheat Sheet — tokens are:
 * <ul>
 *   <li>Cryptographically random (UUID-based)</li>
 *   <li>Single-use ({@code used} flag)</li>
 *   <li>Time-bound ({@code expiryDate})</li>
 * </ul>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html">OWASP Forgot Password Cheat Sheet</a>
 */
@Entity
@Table(name = "verification_tokens")
@Audited
public class VerificationToken extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "token_type", nullable = false, length = 30)
    private VerificationTokenType tokenType;

    @Column(name = "expiry_date", nullable = false)
    private Instant expiryDate;

    @Column(name = "used", nullable = false)
    private boolean used = false;

    protected VerificationToken() {
    }

    private VerificationToken(UUID id, UUID userId, String token,
                               VerificationTokenType tokenType, Instant expiryDate) {
        this.id = id;
        this.userId = userId;
        this.token = token;
        this.tokenType = tokenType;
        this.expiryDate = expiryDate;
    }

    public static VerificationToken create(UUID userId, String token,
                                            VerificationTokenType tokenType, Instant expiryDate) {
        return new VerificationToken(UUID.randomUUID(), userId, token, tokenType, expiryDate);
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

    public VerificationTokenType getTokenType() {
        return tokenType;
    }

    public Instant getExpiryDate() {
        return expiryDate;
    }

    public boolean isUsed() {
        return used;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiryDate);
    }

    public void markAsUsed() {
        this.used = true;
    }
}
