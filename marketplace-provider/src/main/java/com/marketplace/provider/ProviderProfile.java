package com.marketplace.provider;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "provider_profiles")
public class ProviderProfile extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "country", length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ProviderStatus status = ProviderStatus.PENDING_VERIFICATION;

    protected ProviderProfile() {
    }

    public ProviderProfile(UUID id, UUID userId, String displayName, String bio, String city, String country) {
        this.id = id;
        this.userId = userId;
        this.displayName = displayName;
        this.bio = bio;
        this.city = city;
        this.country = country;
    }

    public static ProviderProfile create(UUID userId, String displayName, String bio, String city, String country) {
        return new ProviderProfile(UUID.randomUUID(), userId, displayName, bio, city, country);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public String getBio() { return bio; }
    public String getCity() { return city; }
    public String getCountry() { return country; }
    public ProviderStatus getStatus() { return status; }

    public void update(String displayName, String bio, String city, String country) {
        this.displayName = displayName;
        this.bio = bio;
        this.city = city;
        this.country = country;
    }

    public void verify() { this.status = ProviderStatus.ACTIVE; }
    public void suspend() { this.status = ProviderStatus.SUSPENDED; }
    public void reject() { this.status = ProviderStatus.REJECTED; }
}
