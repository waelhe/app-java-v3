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

@Entity
@Table(name = "provider_profiles")
@Audited
public class ProviderProfile extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "bio", length = 1000)
    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ProviderStatus status;

    @Column(name = "user_id")
    private UUID userId;

    /**
     * L36 (realestate systems plan §5): the actor classification —
     * فرد / وسيط مستقل / مكتب. Non-null by design: every profile carries a
     * classification, defaulting to {@link ProviderActorType#INDIVIDUAL}
     * (the V56 DB default backfills pre-L36 rows with the same value).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 30)
    private ProviderActorType actorType = ProviderActorType.INDIVIDUAL;

    /** L36: the optional public office name (the AGENCY persona's display field). */
    @Column(name = "agency_name", length = 200)
    private String agencyName;

    /**
     * L36: the optional public brokerage license text — display only, no
     * legal verification (KYC sits behind its own documented gate).
     */
    @Column(name = "license_number", length = 100)
    private String licenseNumber;

    /**
     * L21 (roadmap §5): the stored rating average, recomputed from the
     * reviews table by the review events and exposed on the provider read.
     * Null until the first review lands.
     */
    @Column(name = "rating_average")
    private Double ratingAverage;

    /**
     * W1 (yelp-level plan §4.4): the GENERAL (organic) companion of
     * {@code rating_average} — the second stored pair refreshed by the
     * same listener from the origin='ORGANIC' aggregate. Null until the
     * first published organic review lands; cleared when the last one
     * leaves the published surface (the recompute-is-truth rule).
     */
    @Column(name = "rating_general_average")
    private Double ratingGeneralAverage;

    /**
     * W1 (§4.4): the count half of the general pair. Non-null by design —
     * a provider with zero published organic reviews carries 0 (V72's
     * NOT NULL DEFAULT), which is exact rather than "unknown".
     */
    @Column(name = "rating_general_count", nullable = false)
    private Long ratingGeneralCount = 0L;

    /**
     * W2 (yelp-level plan §5 — the business page): the ownership
     * verification state — the Yelp «مالك موثّق» badge's lifecycle
     * (G14). Non-null by design: every pre-W2 profile IS unverified
     * (V89's DEFAULT backfill is the honest classification, not an
     * assumption — the V56 actor_type precedent). Display-only trust
     * signal: no privilege attaches to VERIFIED (the profile status
     * lifecycle above stays the gate for listings and inventory).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_state", nullable = false, length = 30)
    private ProviderVerificationState verificationState = ProviderVerificationState.UNVERIFIED;

    protected ProviderProfile() {}

    private ProviderProfile(UUID id, String displayName, String bio, ProviderStatus status, UUID userId,
                            ProviderActorType actorType, String agencyName, String licenseNumber) {
        this.id = id;
        this.displayName = displayName;
        this.bio = bio;
        this.status = status;
        this.userId = userId;
        this.actorType = actorType == null ? ProviderActorType.INDIVIDUAL : actorType;
        this.agencyName = agencyName;
        this.licenseNumber = licenseNumber;
    }

    /**
     * Pre-L36 form (kept so every existing construction site compiles and
     * behaves identically): an unclassified provider IS an individual.
     */
    public static ProviderProfile create(String displayName, String bio, UUID userId) {
        return create(displayName, bio, userId, null, null, null);
    }

    /**
     * L36 full form: the persona fields ride the creation — a null actor
     * type means the individual default (the request surface's optional
     * field, same honest default as the V56 backfill).
     */
    public static ProviderProfile create(String displayName, String bio, UUID userId,
                                         ProviderActorType actorType, String agencyName,
                                         String licenseNumber) {
        return new ProviderProfile(UUID.randomUUID(), displayName, bio, ProviderStatus.PENDING, userId,
                actorType, agencyName, licenseNumber);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBio() {
        return bio;
    }

    public ProviderStatus getStatus() {
        return status;
    }

    public UUID getUserId() {
        return userId;
    }

    public ProviderActorType getActorType() {
        return actorType;
    }

    public String getAgencyName() {
        return agencyName;
    }

    public String getLicenseNumber() {
        return licenseNumber;
    }

    public Double getRatingAverage() {
        return ratingAverage;
    }

    public Double getRatingGeneralAverage() {
        return ratingGeneralAverage;
    }

    public Long getRatingGeneralCount() {
        return ratingGeneralCount;
    }

    /** W2 (§5 — the business page): the ownership-verification badge state. */
    public ProviderVerificationState getVerificationState() {
        return verificationState;
    }

    /**
     * L21 + W1: stores the event-recomputed average (a plain assignment —
     * the source of truth is the aggregate query). A null clears the
     * stored value: the recompute is truth, and "no published reviews"
     * must not keep a stale number (the W1 moderation-hide path depends
     * on this).
     */
    public void applyRatingAverage(Double ratingAverage) {
        this.ratingAverage = ratingAverage;
    }

    /**
     * W1 (§4.4): stores the recomputed general pair in the same locked
     * transaction as its verified sibling — the same clear-on-empty rule.
     */
    public void applyGeneralRating(Double average, long count) {
        this.ratingGeneralAverage = average;
        this.ratingGeneralCount = count;
    }

    /**
     * Pre-L36 form (kept so every existing call site compiles unchanged):
     * the persona fields are untouched.
     */
    public void update(String newDisplayName, String newBio) {
        this.displayName = newDisplayName;
        this.bio = newBio;
    }

    /**
     * L36 full form — PUT replacement semantics per field class (the two
     * documented house precedents on this very surface):
     * <ul>
     *   <li>{@code bio}, {@code agencyName}, {@code licenseNumber} — optional
     *       display strings: full replacement, omitted ({@code null}) clears
     *       (the bio contract this endpoint has always had);</li>
     *   <li>{@code newActorType} — a required classification cannot be
     *       cleared, so {@code null} KEEPS the stored value (the catalog
     *       currency rule: "omitting the field does not reset money
     *       semantics" — a semantic identity field is not silently reset).</li>
     * </ul>
     */
    public void update(String newDisplayName, String newBio, ProviderActorType newActorType,
                       String newAgencyName, String newLicenseNumber) {
        this.displayName = newDisplayName;
        this.bio = newBio;
        if (newActorType != null) {
            this.actorType = newActorType;
        }
        this.agencyName = newAgencyName;
        this.licenseNumber = newLicenseNumber;
    }

    public void verify() {
        this.status.validateTransitionTo(ProviderStatus.VERIFIED);
        this.status = ProviderStatus.VERIFIED;
    }

    /**
     * W2 (yelp-level plan §5 — the business page): the owner submits the
     * verification claim — {@code UNVERIFIED/REJECTED/VERIFIED → PENDING}
     * (the {@link ProviderVerificationState} transition law; the admin
     * surface resolves PENDING from here).
     */
    public void submitForVerification() {
        this.verificationState.validateTransitionTo(ProviderVerificationState.PENDING);
        this.verificationState = ProviderVerificationState.PENDING;
    }

    /**
     * W2: an administrator confirms ownership — {@code PENDING → VERIFIED}
     * (the badge lights; the transition is audited by Envers like every
     * state this entity carries).
     */
    public void confirmVerification() {
        this.verificationState.validateTransitionTo(ProviderVerificationState.VERIFIED);
        this.verificationState = ProviderVerificationState.VERIFIED;
    }

    /**
     * W2: an administrator declines the claim — {@code PENDING → REJECTED}
     * (the owner may submit again; the Envers trail is the record).
     */
    public void rejectVerification() {
        this.verificationState.validateTransitionTo(ProviderVerificationState.REJECTED);
        this.verificationState = ProviderVerificationState.REJECTED;
    }

    public void suspend() {
        this.status.validateTransitionTo(ProviderStatus.SUSPENDED);
        this.status = ProviderStatus.SUSPENDED;
    }
}
