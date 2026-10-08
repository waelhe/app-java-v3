package com.marketplace.institutions;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.Clock;
import java.util.UUID;

/**
 * B-13 (compliance plan C.3 — سجل الجهة): the institution registry's
 * aggregate root, on the {@code NeighborhoodMembership}/{@code Review}
 * house shapes — {@code @Audited} over the full BaseEntity column set
 * from day one, the soft delete keeping the audit trail.
 *
 * <p><b>The seams (all measured house conventions):</b></p>
 * <ul>
 *   <li>{@code representativeId} — the REGISTERING user's id
 *       ({@code users.id} space, the A1 convention): the caller at
 *       registration time, the registry's own manager. One user may
 *       represent several institutions (a school's registrar and its
 *       clinic's director are different people; one charity's office
 *       manager registers both branches).</li>
 *   <li>{@code locationId} — the geo tree node (level 3, the
 *       neighborhood — gated by the service through {@code GeoLookupPort}
 *       exactly like the membership's own anchor; the same single
 *       administrative hierarchy, D-N2). A plain UUID column with NO JPA
 *       relation across module boundaries (the V32/V48/V54 discipline).</li>
 *   <li>{@code verificationState} — the institution's OWN legitimacy
 *       lifecycle ({@link InstitutionVerificationState}), NOT the
 *       membership machinery (which stays in community per the owner's
 *       ruling).</li>
 *   <li>{@code registeredAt} — the domain's own timestamp (the
 *       {@code memberSince} discipline: the product reads it, the audit
 *       columns are infrastructure bookkeeping).</li>
 * </ul>
 */
@Entity
@Table(name = "institutions")
@Audited
public class Institution extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private InstitutionType type;

    /** The registering user's id (users.id space — the registry's manager). */
    @Column(name = "representative_id", nullable = false)
    private UUID representativeId;

    /** The geo tree node — level 3 (neighborhood) only, gated by the service. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    /** The optional free-text street address (the registry's own field; the geo anchor is separate). */
    @Column(name = "address", length = 300)
    private String address;

    /** The optional public contact phone. */
    @Column(name = "phone", length = 20)
    private String phone;

    /** The optional public website (rides the JSON-LD url only when present — never fabricated). */
    @Column(name = "website", length = 300)
    private String website;

    /** The optional description (rides the JSON-LD description only when present). */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_state", nullable = false, length = 20)
    private InstitutionVerificationState verificationState;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    protected Institution() {
    }

    private Institution(UUID id, String name, InstitutionType type,
                        UUID representativeId, UUID locationId, String address,
                        String phone, String website, String description, Clock clock) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.representativeId = representativeId;
        this.locationId = locationId;
        this.address = address;
        this.phone = phone;
        this.website = website;
        this.description = description;
        this.verificationState = InstitutionVerificationState.UNVERIFIED;
        this.registeredAt = clock.instant();
    }

    /**
     * The registration factory: an institution is born UNVERIFIED — the
     * self-declared registry entry, visible on the public board with its
     * state (the honest registry: the trust mark arrives only through
     * the review). The level-3 geo gate and the shape guards live in the
     * service (before any write); this factory is the honest insert
     * shape.
     */
    public static Institution register(String name, InstitutionType type,
                                       UUID representativeId, UUID locationId,
                                       String address, String phone, String website,
                                       String description, Clock clock) {
        return new Institution(UUID.randomUUID(), name, type, representativeId,
                locationId, address, phone, website, description, clock);
    }

    /**
     * The representative-controlled request: UNVERIFIED → PENDING only
     * (the {@code requestVerification} house discipline — a REJECTED
     * claim cannot self-reverse; the recovery lever is the
     * administrator's APPROVE).
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
            throw new IllegalStateException("Only PENDING or REJECTED institutions can be approved");
        }
        verificationState = InstitutionVerificationState.VERIFIED;
    }

    /** REJECT refuses a PENDING claim (the row stays — the honest registry). */
    public void rejectVerification() {
        if (verificationState != InstitutionVerificationState.PENDING) {
            throw new IllegalStateException("Only PENDING institutions can be rejected");
        }
        verificationState = InstitutionVerificationState.REJECTED;
    }

    @Override
    public UUID getId() { return id; }
    public String getName() { return name; }
    public InstitutionType getType() { return type; }
    public UUID getRepresentativeId() { return representativeId; }
    public UUID getLocationId() { return locationId; }
    public String getAddress() { return address; }
    public String getPhone() { return phone; }
    public String getWebsite() { return website; }
    public String getDescription() { return description; }
    public InstitutionVerificationState getVerificationState() { return verificationState; }
    public Instant getRegisteredAt() { return registeredAt; }
}
