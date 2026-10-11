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
 * Phase 1 (the unified plan §10, D-03 — §3.1's P0 «UserRole أحادي» gap):
 * one account's role assignment — the multi-role row that lets one
 * person hold approved role combinations at once (a community member AND
 * a store owner AND a service provider), the safe-for-migration model
 * the plan mandates before any endpoint expansion.
 *
 * <p><b>Module ownership (§4.3):</b> account identity is
 * marketplace-identity's own row, so the assignment lives here — the
 * reasoning that keeps {@code follows} in this module verbatim.
 *
 * <p><b>The role vocabulary stays the V1 closed set</b>
 * (CONSUMER/PROVIDER/ADMIN — no enum widening in this phase): the
 * assignment model is what is new, not the role kinds. V182's CHECK is
 * the SQL-side twin.
 *
 * <p><b>The two-layer lifecycle (the V93/V177 house form):</b> the row is
 * born ACTIVE ({@code revoked_at = null}); a revoke stamps
 * {@code revoked_at} and the row STAYS (the member's own audit record —
 * the V178 withdraw philosophy). A re-grant after a revoke inserts a
 * FRESH row; V182's partial unique index
 * {@code uq_user_role_assignments_one_active} backs the service's
 * duplicate-active read so one (user, role) pair holds at most one
 * ACTIVE assignment at any instant.
 *
 * <p><b>The security wiring (V183):</b> the effective-authorities view
 * unions the ACTIVE (and live) rows into the login-side authority read —
 * an active assignment IS an authority at the next token mint, a revoked
 * one is not. This entity is therefore a security-bearing record: its
 * transitions are gated (the service's ADMIN gate, the A-07 three-layer
 * pattern), audited ({@code @Audited}) and published on the fail-open
 * leg ({@code UserRoleAssignmentRevoked}).
 *
 * <p><b>Cross-module references:</b> none needed — {@code userId} is
 * THIS module's own users.id space (V182's real intra-module FK is the
 * SQL-side enforcement; the service still resolves the user for the
 * clean 404 and the event payload). {@code grantedBy} is the granting
 * administrator's users.id — nullable by contract: NULL is the system's
 * own signature (the migration backfill's).
 *
 * <p>Getters stay package-private — the {@code Follow} shape verbatim:
 * the only readers (the service, the composed view) live in this same
 * package; the entity never crosses the HTTP boundary (the
 * controllersMustNotDependOnJpaEntities gate — the wire speaks
 * {@link RoleAssignmentView}).
 */
@Entity
@Table(name = "user_role_assignments")
@Audited
public class RoleAssignment extends BaseEntity {

    @Id
    private UUID id;

    /** The assigned account — a users.id (this module's own space; no JPA relation). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The assigned role — the V1 closed vocabulary (V182's CHECK is the SQL-side twin). */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private UserRole role;

    /** When the grant took effect (the backfill uses the account's own birthday). */
    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    /** The granting administrator's users.id — NULL = the system (the backfill). */
    @Column(name = "granted_by")
    private UUID grantedBy;

    /** Why the row exists — the closed V182 vocabulary (BACKFILL | ADMIN). */
    @Column(name = "source", nullable = false, length = 50)
    private String source;

    /**
     * The withdrawal stamp — NULL while the assignment is ACTIVE; set
     * (irreversibly) by the revoke transition. The active/withdrawn
     * split is the record's own security semantics (the V183 union
     * predicate).
     */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RoleAssignment() {
        // JPA
    }

    private RoleAssignment(UUID id, UUID userId, UserRole role, Instant grantedAt,
                           UUID grantedBy, String source) {
        if (userId == null || role == null || grantedAt == null || source == null) {
            throw new IllegalArgumentException(
                    "userId, role, grantedAt and source are required");
        }
        this.id = id;
        this.userId = userId;
        this.role = role;
        this.grantedAt = grantedAt;
        this.grantedBy = grantedBy;
        this.source = source;
    }

    /**
     * The factory gate: an assignment without its account, role, grant
     * time or source cannot exist. The duplicate-active rule and the
     * target's existence are the SERVICE's gates — they need the
     * resolved pair and the standing rows, not the raw arguments (the
     * {@code Follow} factory's own reasoning verbatim).
     */
    static RoleAssignment grant(UUID id, UUID userId, UserRole role,
                                Instant grantedAt, UUID grantedBy, String source) {
        return new RoleAssignment(id, userId, role, grantedAt, grantedBy, source);
    }

    /**
     * The revoke transition — the one write the active state accepts.
     * Guarded by {@code IllegalStateException} (the {@code UrgentAlert}
     * withdraw's own guard shape): the service translates the standing
     * row it loaded, so an already-revoked row reaching here is the
     * caller's contract breach, never a silent no-op.
     */
    void revoke(Instant revokedAt) {
        if (this.revokedAt != null) {
            throw new IllegalStateException(
                    "Role assignment is already revoked: " + this.id);
        }
        this.revokedAt = revokedAt;
    }

    UUID getUserId() { return userId; }

    UserRole getRole() { return role; }

    Instant getGrantedAt() { return grantedAt; }

    UUID getGrantedBy() { return grantedBy; }

    String getSource() { return source; }

    Instant getRevokedAt() { return revokedAt; }

    boolean isActive() { return revokedAt == null; }

    @Override
    public UUID getId() { return id; }
}
