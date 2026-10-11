package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
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
 * A verification credential (ADR-0001, plan §Phase 1) — the evidence an
 * account submits for one {@link VerificationCredentialType}, reviewed by an
 * administrator through the {@link VerificationCredentialStatus} state
 * machine.
 *
 * <p><b>The ADR-0001 separation, enforced here by construction:</b> this
 * entity has NO reference to {@link UserRole} — the decision methods change
 * status columns only. The grant of any role stays a separate administrative
 * act on the role stores ({@code UserService} grant/revoke), never a side
 * effect of evidence review (plan: «ترحيل إضافي للبيانات القديمة بلا رفع
 * صلاحيات تلقائي» — and no automatic elevation for fresh evidence either).
 *
 * <p>Plain {@code user_id} UUID column with the DB-level FK (the V170/V170
 * house shape — no JPA relation), Envers-audited ({@code _aud} mirror in
 * V171): the revision trail records who submitted and who decided.
 */
@Entity
@Table(name = "verification_credentials")
@Audited
public class VerificationCredential extends BaseEntity {

    @Id
    private UUID id;

    /** The account the evidence is about (the {@code users} row id). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false, length = 40)
    private VerificationCredentialType credentialType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VerificationCredentialStatus status;

    /** Optional pointer to the evidence artifact the account submitted. */
    @Column(name = "evidence_uri", length = 1024)
    private String evidenceUri;

    /** The submitter's own note (kept verbatim — never overwritten by review). */
    @Column(name = "notes", length = 2000)
    private String notes;

    /** The reviewer's decision note — its own column, never the submitter's. */
    @Column(name = "decision_notes", length = 2000)
    private String decisionNotes;

    @Column(name = "reviewed_by", length = 200)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected VerificationCredential() {
    }

    private VerificationCredential(UUID id, UUID userId, VerificationCredentialType credentialType,
                                   String evidenceUri, String notes) {
        this.id = id;
        this.userId = userId;
        this.credentialType = credentialType;
        this.status = VerificationCredentialStatus.PENDING;
        this.evidenceUri = evidenceUri;
        this.notes = notes;
    }

    /**
     * The birth state: every credential is born {@code PENDING} — review is
     * the only path to any other status.
     */
    public static VerificationCredential submit(UUID userId, VerificationCredentialType credentialType,
                                                String evidenceUri, String notes) {
        return new VerificationCredential(UUID.randomUUID(), userId, credentialType, evidenceUri, notes);
    }

    /** {@code PENDING → APPROVED} — anything else is the 409 contract. */
    public void approve(String reviewer, String decisionNote) {
        requireStatus(VerificationCredentialStatus.PENDING, "approve");
        transitionTo(VerificationCredentialStatus.APPROVED, reviewer, decisionNote);
    }

    /** {@code PENDING → REJECTED} — anything else is the 409 contract. */
    public void reject(String reviewer, String decisionNote) {
        requireStatus(VerificationCredentialStatus.PENDING, "reject");
        transitionTo(VerificationCredentialStatus.REJECTED, reviewer, decisionNote);
    }

    /** {@code APPROVED → REVOKED} — the standing review's withdrawal path. */
    public void revoke(String reviewer, String decisionNote) {
        requireStatus(VerificationCredentialStatus.APPROVED, "revoke");
        transitionTo(VerificationCredentialStatus.REVOKED, reviewer, decisionNote);
    }

    private void requireStatus(VerificationCredentialStatus expected, String action) {
        if (this.status != expected) {
            throw new ConflictException("Cannot " + action + " a credential in status " + this.status
                    + " — expected " + expected);
        }
    }

    private void transitionTo(VerificationCredentialStatus target, String reviewer, String decisionNote) {
        this.status = target;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        if (decisionNote != null && !decisionNote.isBlank()) {
            this.decisionNotes = decisionNote;
        }
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public VerificationCredentialType getCredentialType() { return credentialType; }
    public VerificationCredentialStatus getStatus() { return status; }
    public String getEvidenceUri() { return evidenceUri; }
    public String getNotes() { return notes; }
    public String getDecisionNotes() { return decisionNotes; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
}
