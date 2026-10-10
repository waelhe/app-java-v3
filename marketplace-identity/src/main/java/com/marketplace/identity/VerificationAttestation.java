package com.marketplace.identity;

import com.marketplace.shared.api.TrustType;
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
 * Phase 1 (the unified plan §10) — one verification attestation: the
 * account-level trust fact behind §6.5's «الثقة الأربع», carried by the
 * shared-api {@link TrustType} vocabulary (the enum's literal §6.5
 * semantics; «لا خلط بينها» — four separate facts, never one mega
 * «موثق» boolean, §4.5's explicit prohibition).
 *
 * <p><b>What this row IS and is NOT (the one-home rule, §4.2):</b> the
 * attestation is the account's trust FACT plus its {@code evidence_ref}
 * pointing at the verifying workflow's own record — the membership
 * verification stays community's, the business files stay provider's,
 * the official entities stay institutions' (§6.2: official-entity
 * verification is separate from a commercial-account verification or a
 * community membership). Nothing is copied here.
 *
 * <p><b>The endorsement semantics (D-14):</b> a
 * {@code COMMUNITY_ENDORSEMENT} attestation is an ADDITIONAL signal —
 * never a membership condition — and its evidence ref points at the
 * recommendation/endorsement record attributed to a real person.
 *
 * <p><b>The lifecycle (V184's closed state machine, the
 * {@link VerificationAttestationState} javadoc):</b> born PENDING (the
 * self-service request with its evidence); the reviewer's decision
 * moves it to GRANTED (granted_at + granted_by recorded) or REJECTED;
 * a granted fact can be REVOKED. The row and its Envers history stay
 * through every transition — the withdrawal is stamped, never erased.
 *
 * <p><b>The two-layer uniqueness (the V93/V177 house form):</b> the
 * service's GRANTED-duplicate read comes first; V184's partial unique
 * index {@code uq_verification_attestations_one_active} on
 * (subject_user_id, trust_type) WHERE state = 'GRANTED' is the
 * concurrent backstop. A REVOKED or REJECTED row frees the (subject,
 * type) pair — a fresh request after a rejection is legal by
 * construction.
 *
 * <p><b>Cross-module references:</b> none needed — the subject is THIS
 * module's own users.id space (V184's real intra-module FK; the service
 * resolves the account for the clean 404). {@code grantedBy} is the
 * reviewer's users.id — NULL = the system's own signature.
 *
 * <p>Getters stay package-private — the {@code Follow} shape verbatim:
 * the wire speaks {@link VerificationAttestationView} only (the
 * controllersMustNotDependOnJpaEntities gate).
 */
@Entity
@Table(name = "verification_attestations")
@Audited
public class VerificationAttestation extends BaseEntity {

    @Id
    private UUID id;

    /** The trust subject — a users.id (this module's own space; no JPA relation). */
    @Column(name = "subject_user_id", nullable = false)
    private UUID subjectUserId;

    /** The §6.5 trust type — the closed shared vocabulary (V184's CHECK is the SQL-side twin). */
    @Enumerated(EnumType.STRING)
    @Column(name = "trust_type", nullable = false, length = 40)
    private TrustType trustType;

    /** The lifecycle state (V184's closed machine — see {@link VerificationAttestationState}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private VerificationAttestationState state;

    /**
     * The evidence reference — a pointer into the verifying workflow's
     * own record (never a copy of it). The service requires it on the
     * request path (the evidence IS the essence of «أدلة ارتباط»).
     */
    @Column(name = "evidence_ref", length = 500)
    private String evidenceRef;

    /** The reviewer's positive decision stamp — set exactly once, at the GRANTED transition. */
    @Column(name = "granted_at")
    private Instant grantedAt;

    /** The withdrawal stamp — set exactly once, at the REVOKED transition. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** The granting reviewer's users.id — NULL = the system. */
    @Column(name = "granted_by")
    private UUID grantedBy;

    protected VerificationAttestation() {
        // JPA
    }

    private VerificationAttestation(UUID id, UUID subjectUserId, TrustType trustType,
                                    String evidenceRef) {
        if (subjectUserId == null || trustType == null
                || evidenceRef == null || evidenceRef.isBlank()) {
            throw new IllegalArgumentException(
                    "subjectUserId, trustType and a non-blank evidenceRef are required");
        }
        this.id = id;
        this.subjectUserId = subjectUserId;
        this.trustType = trustType;
        this.state = VerificationAttestationState.PENDING;
        this.evidenceRef = evidenceRef;
    }

    /**
     * The birth transition — PENDING with its evidence. The account's
     * existence and the GRANTED-duplicate rule are the SERVICE's gates
     * (the resolved pair and the standing rows, not the raw arguments).
     */
    static VerificationAttestation request(UUID id, UUID subjectUserId, TrustType trustType,
                                           String evidenceRef) {
        return new VerificationAttestation(id, subjectUserId, trustType, evidenceRef);
    }

    /** PENDING → GRANTED: the reviewer's positive decision (guarded — see the lifecycle). */
    void grant(UUID reviewerId, Instant grantedAt) {
        if (this.state != VerificationAttestationState.PENDING) {
            throw new IllegalStateException(
                    "Attestation is not PENDING: " + this.id + " (" + this.state + ")");
        }
        this.state = VerificationAttestationState.GRANTED;
        this.grantedAt = grantedAt;
        this.grantedBy = reviewerId;
    }

    /** PENDING → REJECTED: the reviewer's negative decision (guarded — see the lifecycle). */
    void reject() {
        if (this.state != VerificationAttestationState.PENDING) {
            throw new IllegalStateException(
                    "Attestation is not PENDING: " + this.id + " (" + this.state + ")");
        }
        this.state = VerificationAttestationState.REJECTED;
    }

    /** GRANTED → REVOKED: the withdrawal of a granted fact (guarded — see the lifecycle). */
    void revoke(Instant revokedAt) {
        if (this.state != VerificationAttestationState.GRANTED) {
            throw new IllegalStateException(
                    "Attestation is not GRANTED: " + this.id + " (" + this.state + ")");
        }
        this.state = VerificationAttestationState.REVOKED;
        this.revokedAt = revokedAt;
    }

    UUID getSubjectUserId() { return subjectUserId; }

    TrustType getTrustType() { return trustType; }

    VerificationAttestationState getState() { return state; }

    String getEvidenceRef() { return evidenceRef; }

    Instant getGrantedAt() { return grantedAt; }

    Instant getRevokedAt() { return revokedAt; }

    UUID getGrantedBy() { return grantedBy; }

    @Override
    public UUID getId() { return id; }
}
