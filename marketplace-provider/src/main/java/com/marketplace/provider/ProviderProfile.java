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

    protected ProviderProfile() {}

    private ProviderProfile(UUID id, String displayName, String bio, ProviderStatus status) {
        this.id = id;
        this.displayName = displayName;
        this.bio = bio;
        this.status = status;
    }

    public static ProviderProfile create(String displayName, String bio) {
        return new ProviderProfile(UUID.randomUUID(), displayName, bio, ProviderStatus.PENDING);
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

    public void update(String newDisplayName, String newBio) {
        this.displayName = newDisplayName;
        this.bio = newBio;
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
