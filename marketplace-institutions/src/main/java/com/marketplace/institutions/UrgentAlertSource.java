package com.marketplace.institutions;

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
 * D-3/D-4 (the delegated urgent alert — CMP-46/JT-10): one DELEGATED
 * official source (بلدية، دفاع مدني، مرافق، سلطة صحية، سلطة تعليمية —
 * the {@link UrgentAlertSourceType} vocabulary), on the
 * {@link Institution} house shape verbatim — {@code @Audited} over the
 * full BaseEntity column set from day one, the soft delete keeping the
 * audit trail.
 *
 * <p><b>The verification machine is the registry's own quartet VERBATIM
 * (the V154/V97 discipline — one trust vocabulary for the whole
 * platform):</b> born UNVERIFIED (the honest registry), PENDING on the
 * request, and the administrator's verdict — VERIFIED (the delegation
 * lands) or REJECTED (the row stays, the mark never lands). The
 * delegation IS that verdict: a source may publish urgent alerts
 * ({@code UrgentAlertService.publishAlert}) ONLY in the VERIFIED state,
 * and the active-alerts read ({@code UrgentAlertsPort.findActive})
 * refuses anything else — the deterministic eligibility AC-20-01 names
 * (a trusted source is a display precondition, not a preference).</p>
 *
 * <p><b>من يفوض؟ نفس بوابة admin الحالية</b> — the same class-level
 * {@code hasRole('ADMIN')} gate that moves the institution registry's
 * verdicts moves this one ({@code UrgentAlertAdminController}); there is
 * no representative self-service here by design — an official body
 * cannot self-declare its own authority.</p>
 */
@Entity
@Table(name = "delegated_urgent_sources")
@Audited
public class UrgentAlertSource extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 40)
    private UrgentAlertSourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_state", nullable = false, length = 30)
    private InstitutionVerificationState verificationState;

    protected UrgentAlertSource() {
    }

    private UrgentAlertSource(UUID id, String name, UrgentAlertSourceType sourceType) {
        this.id = id;
        this.name = name;
        this.sourceType = sourceType;
        this.verificationState = InstitutionVerificationState.UNVERIFIED;
    }

    /**
     * The delegation factory: a source is born UNVERIFIED — the honest
     * registry (the same shape {@link Institution#register} rides): the
     * entry exists, the authority does not — the trust mark arrives only
     * through the administrative review. The name/type shape guards live
     * in the service (before any write); this factory is the honest
     * insert shape.
     */
    public static UrgentAlertSource delegate(String name, UrgentAlertSourceType sourceType) {
        return new UrgentAlertSource(UUID.randomUUID(), name, sourceType);
    }

    /**
     * The administrator's review request: UNVERIFIED → PENDING only (the
     * {@code requestVerification} house discipline — a REJECTED claim
     * cannot self-reverse; the recovery lever is the administrator's
     * APPROVE). The machine is {@link Institution#requestVerification}'s
     * twin verbatim.
     */
    public void requestVerification() {
        if (verificationState == InstitutionVerificationState.UNVERIFIED) {
            verificationState = InstitutionVerificationState.PENDING;
        }
    }

    /** The verdict's recovery lever: APPROVE admits a PENDING claim and RE-ADMITS a REJECTED one. */
    public void approveVerification() {
        if (verificationState != InstitutionVerificationState.PENDING
                && verificationState != InstitutionVerificationState.REJECTED) {
            throw new IllegalStateException("Only PENDING or REJECTED sources can be approved");
        }
        verificationState = InstitutionVerificationState.VERIFIED;
    }

    /** REJECT refuses a PENDING claim (the row stays — the honest registry). */
    public void rejectVerification() {
        if (verificationState != InstitutionVerificationState.PENDING) {
            throw new IllegalStateException("Only PENDING sources can be rejected");
        }
        verificationState = InstitutionVerificationState.REJECTED;
    }

    @Override
    public UUID getId() { return id; }
    public String getName() { return name; }
    public UrgentAlertSourceType getSourceType() { return sourceType; }
    public InstitutionVerificationState getVerificationState() { return verificationState; }
}
