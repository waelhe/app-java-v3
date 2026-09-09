package com.marketplace.catalog;

import com.marketplace.shared.api.Currencies;
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

    /** Money currency of the listing price — ISO 4217 alphabetic code. */
    public static final String DEFAULT_CURRENCY = Currencies.DEFAULT_CODE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ListingStatus status = ListingStatus.DRAFT;

    /**
     * I6 (internal free plan §6, roadmap D1): guest capacity of the listing.
     * {@code null} = capacity not declared — such a listing never matches a
     * guests search criterion (NULL >= N is not true). Positivity is a
     * three-layer gate: request Bean Validation ({@code @Positive}), this
     * entity's factory/update validation, and the V44 CHECK constraint.
     */
    @Column(name = "max_guests")
    private Integer maxGuests;

    protected ProviderListing() {
    }

    public ProviderListing(UUID id, UUID providerId, String title, String description,
                           String category, Long priceCents) {
        this(id, providerId, title, description, category, priceCents, null);
    }

    ProviderListing(UUID id, UUID providerId, String title, String description,
                    String category, Long priceCents, String currency) {
        this(id, providerId, title, description, category, priceCents, currency, null);
    }

    ProviderListing(UUID id, UUID providerId, String title, String description,
                    String category, Long priceCents, String currency, Integer maxGuests) {
        this.id = id;
        this.providerId = providerId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.priceCents = priceCents;
        this.currency = Currencies.normalizeOrDefault(currency, DEFAULT_CURRENCY);
        this.maxGuests = requirePositiveGuests(maxGuests);
    }

    public static ProviderListing create(UUID providerId, String title, String description,
                                         String category, Long priceCents) {
        return create(providerId, title, description, category, priceCents, null);
    }

    /**
     * Creates a listing priced in the given ISO 4217 currency. Blank/null
     * currency keeps the house default {@link #DEFAULT_CURRENCY}; an invalid
     * code fails fast (defence in depth behind the {@code @IsoCurrencyCode}
     * request validation).
     */
    public static ProviderListing create(UUID providerId, String title, String description,
                                         String category, Long priceCents, String currency) {
        return create(providerId, title, description, category, priceCents, currency, null);
    }

    /**
     * I6: full creation form with the optional guest capacity —
     * {@code null} leaves capacity undeclared; a non-positive value is
     * rejected here (the entity's own floor, behind the request-level
     * {@code @Positive} and above the V44 CHECK constraint).
     */
    public static ProviderListing create(UUID providerId, String title, String description,
                                         String category, Long priceCents, String currency,
                                         Integer maxGuests) {
        return new ProviderListing(UUID.randomUUID(), providerId, title, description, category,
                priceCents, currency, maxGuests);
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
    public Integer getMaxGuests() { return maxGuests; }

    public void update(String title, String description, String category, Long priceCents) {
        update(title, description, category, priceCents, null);
    }

    /**
     * Updates the listing including its ISO 4217 currency. Blank/null keeps
     * the stored currency (an update that omits the field does not reset
     * money semantics); an explicit valid code re-prices the listing.
     */
    public void update(String title, String description, String category, Long priceCents,
                       String currency) {
        update(title, description, category, priceCents, currency, null);
    }

    /**
     * I6: the full update form. Capacity follows the currency contract
     * verbatim: {@code null} keeps the stored capacity (an update that
     * omits the field does not reset it); an explicit positive value
     * re-declares it; a non-positive value is rejected here (the entity
     * floor).
     */
    public void update(String title, String description, String category, Long priceCents,
                       String currency, Integer maxGuests) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.priceCents = priceCents;
        if (currency != null && !currency.isBlank()) {
            this.currency = Currencies.normalize(currency);
        }
        if (maxGuests != null) {
            this.maxGuests = requirePositiveGuests(maxGuests);
        }
    }

    /**
     * The capacity floor shared by construction and update: {@code null}
     * passes (undeclared), any non-positive value is rejected — the same
     * value contract the V44 CHECK constraint enforces at the database.
     */
    private static Integer requirePositiveGuests(Integer guests) {
        if (guests != null && guests <= 0) {
            throw new IllegalArgumentException("max guests must be positive when provided");
        }
        return guests;
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