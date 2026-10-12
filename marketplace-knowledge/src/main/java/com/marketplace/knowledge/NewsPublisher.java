package com.marketplace.knowledge;

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
 * D-3 (JT-19/D-30 — «أخبار محلية»): the news publisher's aggregate root,
 * on the {@code Institution}/{@code KnowledgeEntry} house shapes —
 * {@code @Audited} over the full BaseEntity column set from day one, the
 * soft delete keeping the audit trail. An outlet the ADMINISTRATOR
 * registers and administratively verifies: born UNVERIFIED (the honest
 * registry — the trust mark arrives only through the review), and the
 * verdict's only mover is the admin surface ({@code
 * NewsAdminController.verifyPublisher}).
 *
 * <p><b>The seams (all measured house conventions):</b></p>
 * <ul>
 *   <li>{@code verificationState} — the outlet's own legitimacy
 *       lifecycle ({@link NewsPublisherState}), the V154 quadruple
 *       verbatim; NOT the institutions machinery (that stays in its own
 *       module per the B-13 ruling) — a different question: is THIS
 *       OUTLET a legitimate news publisher.</li>
 *   <li>{@code websiteUrl} — the optional public site of the outlet
 *       (rides the response only when present — never fabricated).</li>
 *   <li>No self-service flow in this wave: PENDING sits in the
 *       vocabulary for the future registration flow (the V154 widening
 *       discipline). A VERIFIED publisher is the one state that may
 *       publish {@link NewsItem}s — the delegated-source gate the
 *       urgent-alert wave rides too.</li>
 * </ul>
 */
@Entity
@Table(name = "news_publishers")
@Audited
public class NewsPublisher extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_state", nullable = false, length = 30)
    private NewsPublisherState verificationState;

    protected NewsPublisher() {
    }

    private NewsPublisher(UUID id, String name, String websiteUrl) {
        this.id = id;
        this.name = name;
        this.websiteUrl = websiteUrl;
        this.verificationState = NewsPublisherState.UNVERIFIED;
    }

    /**
     * The registration factory: a publisher is born UNVERIFIED — the
     * honest registry (the trust mark arrives only through the
     * administrative review). This factory is the honest insert shape;
     * every gate lives in the service before the write.
     */
    public static NewsPublisher register(String name, String websiteUrl) {
        return new NewsPublisher(UUID.randomUUID(), name, websiteUrl);
    }

    /**
     * APPROVE lands the trust mark: an UNVERIFIED admin registration, a
     * future PENDING claim, or a REJECTED one (the recovery lever — the
     * house's re-admission discipline) moves to VERIFIED. An already
     * VERIFIED outlet answers {@code IllegalStateException} (the
     * service's 409 with the entity's own transition words).
     */
    public void approveVerification() {
        if (verificationState == NewsPublisherState.VERIFIED) {
            throw new IllegalStateException("News publisher is already VERIFIED");
        }
        verificationState = NewsPublisherState.VERIFIED;
    }

    /**
     * REJECT refuses the claim: an UNVERIFIED (or future PENDING)
     * registration moves to REJECTED (the row stays — the honest
     * registry, the mark never lands). A VERIFIED outlet is NOT
     * rejectable here (revocation is not this wave's lever — the
     * per-item withdrawal is) and a REJECTED one answers {@code
     * IllegalStateException} (already refused).
     */
    public void rejectVerification() {
        if (verificationState == NewsPublisherState.VERIFIED) {
            throw new IllegalStateException("A VERIFIED news publisher cannot be rejected — withdraw its news items instead");
        }
        if (verificationState == NewsPublisherState.REJECTED) {
            throw new IllegalStateException("News publisher is already REJECTED");
        }
        verificationState = NewsPublisherState.REJECTED;
    }

    @Override
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getWebsiteUrl() { return websiteUrl; }
    public NewsPublisherState getVerificationState() { return verificationState; }
}
