package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Single-use recovery code for MFA.
 * <p>Follows OWASP MFA Cheat Sheet -- recovery codes are:
 * <ul>
 *   <li>Cryptographically random</li>
 *   <li>Stored as hashes (never plaintext)</li>
 *   <li>Single-use</li>
 * </ul>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Multifactor_Authentication_Cheat_Sheet.html">OWASP MFA Cheat Sheet</a>
 */
@Entity
@Table(name = "recovery_codes")
@Audited
public class RecoveryCode extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, length = 128)
    private String codeHash;

    @Column(name = "used", nullable = false)
    private boolean used = false;

    protected RecoveryCode() {
    }

    private RecoveryCode(UUID id, UUID userId, String codeHash) {
        this.id = id;
        this.userId = userId;
        this.codeHash = codeHash;
    }

    public static RecoveryCode create(UUID userId, String codeHash) {
        return new RecoveryCode(UUID.randomUUID(), userId, codeHash);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public boolean isUsed() {
        return used;
    }

    public void markUsed() {
        this.used = true;
    }
}
