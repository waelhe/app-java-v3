package com.marketplace.provider;

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
 * W5b (the journeys register's J29 row — تفويض فريق مزود الخدمة بصلاحيات
 * ونطاق): one delegated team seat — the {@code provider_team_members} row
 * (V234). The register's own CONFIRMED GAP wording: «تفويض فريق/فرع بصلاحيات
 * ونطاق — غير منفذ» — this row IS the delegation: a member user, a role
 * class, and the two-state lifecycle whose only exit is the owner's
 * revocation.
 *
 * <p><b>The identity module boundary (V234's own law):</b> {@code
 * member_user_id} is a PLAIN UUID — no FK to {@code users(id)}. The provider
 * module resolves no identity here: the id arrives resolved (the owner
 * delegates by user id), and the identity module's own surfaces remain the
 * only place a user row is read. The module's {@code provider_id} DOES
 * reference its own {@code provider_profiles(id)} — the V96
 * business-page pattern (a child row of a profile the module owns).
 *
 * <p><b>One live seat per member per provider:</b> the partial unique index
 * {@code uq_provider_team_one_live} holds over {@code status='ACTIVE' AND
 * is_deleted=FALSE} only — a REVOKED row never blocks a later re-add (the
 * slot is the live delegation, not the row's history). The service's
 * duplicate gate answers 409 BEFORE any write; the index is the concurrent
 * writer's backstop (the V70 identity shape, one predicate deeper).
 *
 * <p><b>The owner needs no delegation:</b> the service refuses
 * {@code member == owner} with 409 — the owner's authority is the
 * profile's {@code user_id}, never a membership row.
 */
@Entity
@Table(name = "provider_team_members")
@Audited
public class ProviderTeamMember extends BaseEntity {

    @Id
    private UUID id;

    /** The owning provider profile — the business this seat acts for. */
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /**
     * The delegated user — a plain UUID (the identity module boundary; no
     * FK, the same boundary {@code provider_profiles.user_id} keeps).
     */
    @Column(name = "member_user_id", nullable = false)
    private UUID memberUserId;

    /** The authority's class — MANAGER or EDITOR (the V234 CHECK pins it). */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ProviderTeamRole role;

    /**
     * The seat's lifecycle — ACTIVE until the owner revokes (the only
     * legal exit; the Envers trail keeps the history the row lays down).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProviderTeamMemberStatus status;

    /**
     * The explicit delegation attribution — the acting owner who added the
     * member (the resolved user id as text). Distinct from the auditing
     * listener's {@code created_by} (the authentication subject): the
     * delegation's honest record is WHO exercised the owner's authority,
     * not which token shape carried it.
     */
    @Column(name = "added_by", length = 200)
    private String addedBy;

    protected ProviderTeamMember() {
    }

    private ProviderTeamMember(UUID id, UUID providerId, UUID memberUserId,
                               ProviderTeamRole role, String addedBy) {
        this.id = id;
        this.providerId = providerId;
        this.memberUserId = memberUserId;
        this.role = role;
        this.addedBy = addedBy;
        this.status = ProviderTeamMemberStatus.ACTIVE;
    }

    /**
     * Factory for the owner's write surface: a seat is born ACTIVE (the
     * honest registry — there is no "pending" state in the first cut; the
     * owner's add IS the grant), with the whole shape validated once at
     * construction — the entity never holds a form the V234 CHECKs would
     * reject (fail-loud at the boundary, the {@code BusinessHour.create}
     * rule).
     */
    public static ProviderTeamMember create(UUID providerId, UUID memberUserId,
                                           ProviderTeamRole role, String addedBy) {
        if (providerId == null || memberUserId == null || role == null) {
            throw new IllegalArgumentException(
                    "providerId, memberUserId and role are all required");
        }
        return new ProviderTeamMember(UUID.randomUUID(), providerId, memberUserId,
                role, addedBy == null || addedBy.isBlank() ? null : addedBy.trim());
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getMemberUserId() {
        return memberUserId;
    }

    public ProviderTeamRole getRole() {
        return role;
    }

    public ProviderTeamMemberStatus getStatus() {
        return status;
    }

    public String getAddedBy() {
        return addedBy;
    }

    /** The seat's single exit: ACTIVE → REVOKED (any other move answers 409). */
    public void revoke() {
        this.status.validateTransitionTo(ProviderTeamMemberStatus.REVOKED);
        this.status = ProviderTeamMemberStatus.REVOKED;
    }

    /** Whether this seat currently carries its authority — the gate's own read. */
    public boolean isActive() {
        return status == ProviderTeamMemberStatus.ACTIVE;
    }
}
