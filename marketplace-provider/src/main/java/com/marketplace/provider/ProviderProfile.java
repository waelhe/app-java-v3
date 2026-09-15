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

    /** L21: stores the event-recomputed average (a plain assignment — the source of truth is the aggregate query). */
    public void applyRatingAverage(double ratingAverage) {
        this.ratingAverage = ratingAverage;
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

    public void suspend() {
        this.status.validateTransitionTo(ProviderStatus.SUSPENDED);
        this.status = ProviderStatus.SUSPENDED;
    }
}
