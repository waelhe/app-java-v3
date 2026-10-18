package com.marketplace.provider;

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
 * J14 (the journeys register — «مطالبة صاحب العمل بالملف»): one member's
 * claim on one pre-seeded business page, on the {@link BusinessHour}/
 * {@code UrgentAlert} house shapes — {@code @Audited} over the full
 * BaseEntity column set from day one, factory-method construction, and
 * the state machine's illegal transitions answered loudly.
 *
 * <p><b>The cycle this row rides (the register's own words: «لا دورة
 * مطالبة/تحقق/اعتراض مقيسة» — built):</b> the platform pre-seeds the
 * claimable page (a provider_profiles row with {@code user_id} NULL,
 * {@code ProviderService}-shaped but admin-created); a member submits
 * THIS row (born PENDING, one live claim per page — the V233 unique
 * partial index); an administrator approves (the ownership handover:
 * {@code provider_profiles.user_id = claimantUserId}, {@code claimed_at}
 * set) or rejects (the claimant may submit a fresh claim); the claimant
 * may withdraw their own PENDING claim at any time. Every state flip is
 * an Envers revision — the trail IS the record.
 *
 * <p><b>The seams (the house's measured conventions):</b>
 * <ul>
 *   <li>{@code providerId} — the in-module aggregate reference (the V96
 *       {@code business_hours.provider_id} shape): a plain UUID column
 *       in Java, a real FK in SQL — the claim and its page are one
 *       aggregate inside the provider module's own boundary.</li>
 *   <li>{@code claimantUserId} — a PLAIN UUID, no JPA relation and no
 *       SQL FK (the V32/V48/V83 cross-module discipline: the identity
 *       module owns the users table; this module references its ids,
 *       never its rows).</li>
 *   <li>{@code contactChannel} — HOW the reviewer reaches the claimant
 *       back (a phone, an email, an in-app handle — the claim's own
 *       declared channel, never assumed from the account).</li>
 *   <li>{@code decidedBy}/{@code decisionNote}/{@code decidedAt} — the
 *       decision's attribution: the administrator's verdict on
 *       APPROVE/REJECT, the CLAIMANT's own hand on WITHDRAWN (the V230
 *       {@code order_returns.resolution_note} shape; PENDING carries no
 *       verdict — the V233 CHECK pins the shape).</li>
 * </ul>
 */
@Entity
@Table(name = "provider_claims")
@Audited
public class ProviderClaim extends BaseEntity {

    @Id
    private UUID id;

    /** The claimed business page — same-module aggregate (a real FK in V233). */
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /** The claiming member — the identity module's id space (a plain UUID, no FK). */
    @Column(name = "claimant_user_id", nullable = false)
    private UUID claimantUserId;

    /** The claimant's declared reach-back channel (how the reviewer contacts them). */
    @Column(name = "contact_channel", nullable = false, length = 200)
    private String contactChannel;

    /** The claim's own words — why this page is theirs. */
    @Column(name = "note", length = 2000)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private ProviderClaimState state;

    /** Who decided — the administrator (APPROVED/REJECTED) or the claimant (WITHDRAWN). */
    @Column(name = "decided_by", length = 200)
    private String decidedBy;

    /** The verdict's own words (absent on withdrawal — the claimant owes no one a reason). */
    @Column(name = "decision_note", length = 2000)
    private String decisionNote;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected ProviderClaim() {
    }

    private ProviderClaim(UUID id, UUID providerId, UUID claimantUserId,
                          String contactChannel, String note) {
        this.id = id;
        this.providerId = providerId;
        this.claimantUserId = claimantUserId;
        this.contactChannel = contactChannel;
        this.note = note;
        this.state = ProviderClaimState.PENDING;
    }

    /**
     * The submission factory: born PENDING (the machine's only entry
     * state). Validates the claim's own shape ONCE, at construction —
     * the entity never holds a shape the V233 CHECKs would reject
     * (fail-loud at the boundary, the {@link BusinessHour#create} rule).
     * The one-live-claim law is NOT re-checked here: it is the service's
     * pre-write gate with the unique index as the race backstop.
     */
    public static ProviderClaim submit(UUID providerId, UUID claimantUserId,
                                       String contactChannel, String note) {
        if (providerId == null || claimantUserId == null) {
            throw new IllegalArgumentException("providerId and claimantUserId are both required");
        }
        if (contactChannel == null || contactChannel.isBlank()
                || contactChannel.trim().length() > 200) {
            throw new IllegalArgumentException(
                    "contactChannel is required (a non-blank string of at most 200 characters)");
        }
        if (note != null && note.trim().length() > 2000) {
            throw new IllegalArgumentException("note must be at most 2000 characters");
        }
        return new ProviderClaim(UUID.randomUUID(), providerId, claimantUserId,
                contactChannel.trim(), note == null ? null : note.trim());
    }

    /**
     * The verdict's ONLY mover (the {@code UrgentAlertSource} review
     * discipline verbatim): APPROVE — the ownership handover's claim-side
     * half (the service links the profile in the same transaction); REJECT
     * — the claimant may submit a fresh claim. Any other state answers the
     * machine's own {@link IllegalStateException} (the caller renders the
     * house 409).
     */
    public void approve(String decidedBy, String decisionNote, Instant decidedAt) {
        decide(ProviderClaimState.APPROVED, decidedBy, decisionNote, decidedAt);
    }

    /**
     * The refusal lever: {@code PENDING → REJECTED} with the verdict's
     * own words. The claimant's recovery path is a NEW claim — the
     * live-claim index no longer blocks once this lands.
     */
    public void reject(String decidedBy, String decisionNote, Instant decidedAt) {
        decide(ProviderClaimState.REJECTED, decidedBy, decisionNote, decidedAt);
    }

    /**
     * The claimant's own recovery lever: {@code PENDING → WITHDRAWN}.
     * The decider here is the CLAIMANT (their user id as the attribution
     * string — the identity space's own actor form); no decision note is
     * owed. The page becomes claimable again the moment this lands.
     */
    public void withdraw(String decidedBy, Instant decidedAt) {
        decide(ProviderClaimState.WITHDRAWN, decidedBy, null, decidedAt);
    }

    private void decide(ProviderClaimState target, String decidedBy,
                        String decisionNote, Instant decidedAt) {
        this.state.validateTransitionTo(target);
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("The decision's actor (decidedBy) is required");
        }
        this.state = target;
        this.decidedBy = decidedBy;
        this.decisionNote = decisionNote;
        this.decidedAt = decidedAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getClaimantUserId() {
        return claimantUserId;
    }

    public String getContactChannel() {
        return contactChannel;
    }

    public String getNote() {
        return note;
    }

    public ProviderClaimState getState() {
        return state;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
