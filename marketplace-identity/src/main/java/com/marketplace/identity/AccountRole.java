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
 * D-03 (community platform execution plan Stage 1): one row of an
 * account's role SET — the authoritative multi-role record the
 * {@code users.role} primary-role mirror complements. One account may hold
 * several roles; the operative authorization is minted from the full set
 * (the login-side {@code auth_authorities} projection this module keeps in
 * sync — the S2/N4/N6 documented two-store contract, now set-valued).
 *
 * <p><b>Entity-shape decisions:</b>
 * <ul>
 *   <li>A bare {@code user_id} FK instead of a bidirectional association:
 *     the {@code User} read cache hands back DETACHED copies (the
 *     documented {@code updateUserRole} read-the-source-of-truth
 *     discipline) — a managed collection on the cached aggregate invites
 *     exactly the detached-mutation trap that discipline exists for. The
 *     repository is the fresh-read path.</li>
 *   <li>{@code BaseEntity} (house pattern): optimistic locking, auditing
 *     columns, and Hibernate 7 {@code @SoftDelete} — a revoke is a soft
 *     delete, the {@code user_roles_aud} trail keeps the history, and the
 *     partial unique index only sees live rows so a re-grant after a
 *     revoke never collides with its own past.</li>
 *   <li>{@code @Audited}: every grant/revoke/replace lands in
 *     {@code user_roles_aud} with its actor — the plan's audit criterion,
 *     V24-pattern table (V182).</li>
 * </ul>
 */
@Entity
@Table(name = "user_roles")
@Audited
public class AccountRole extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "granted_by", nullable = false, length = 200)
    private String grantedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private RoleGrantSource source;

    protected AccountRole() {
    }

    AccountRole(UUID id, UUID userId, UserRole role, String grantedBy, RoleGrantSource source) {
        this.id = id;
        this.userId = userId;
        this.role = role;
        this.grantedAt = Instant.now();
        this.grantedBy = grantedBy;
        this.source = source;
    }

    static AccountRole grant(UUID userId, UserRole role, String grantedBy, RoleGrantSource source) {
        return new AccountRole(UUID.randomUUID(), userId, role, grantedBy, source);
    }

    @Override
    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }

    public UserRole getRole() { return role; }

    public Instant getGrantedAt() { return grantedAt; }

    public String getGrantedBy() { return grantedBy; }

    public RoleGrantSource getSource() { return source; }
}
