package com.marketplace.catalog;

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
@Table(name = "provider_listings")
@Audited
public class ProviderListing extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "category", nullable = false, length = 100)
    private String category;

    @Column(name = "price_cents", nullable = false)
    private Long priceCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "SAR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ListingStatus status = ListingStatus.DRAFT;

    protected ProviderListing() {
    }

    public ProviderListing(UUID id, UUID providerId, String title, String description,
                           String category, Long priceCents) {
        this.id = id;
        this.providerId = providerId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.priceCents = priceCents;
    }

    public static ProviderListing create(UUID providerId, String title, String description,
                                         String category, Long priceCents) {
        return new ProviderListing(UUID.randomUUID(), providerId, title, description, category, priceCents);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getProviderId() { return providerId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public Long getPriceCents() { return priceCents; }
    public String getCurrency() { return currency; }
    public ListingStatus getStatus() { return status; }

    public void update(String title, String description, String category, Long priceCents) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.priceCents = priceCents;
    }

    public void activate() {
        this.status.validateTransitionTo(ListingStatus.ACTIVE);
        this.status = ListingStatus.ACTIVE;
    }
    public void pause() {
        this.status.validateTransitionTo(ListingStatus.PAUSED);
        this.status = ListingStatus.PAUSED;
    }
    public void archive() {
        this.status.validateTransitionTo(ListingStatus.ARCHIVED);
        this.status = ListingStatus.ARCHIVED;
    }
}