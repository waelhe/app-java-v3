package com.marketplace.provider;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): one row of the declared
 * services list — the {@code provider_services} table (V88). The Yelp
 * «قائمة الطعام» analog the plan names: service, duration, price —
 * «قائمة خدمات معلنة provider_services».
 *
 * <p><b>A menu, not a bookable product:</b> booking rides the catalog's
 * listings and the availability module's slots; this row is the business's
 * own display statement of what it offers — the public page renders it as
 * the services block, in {@code position} order (the D-N5 deterministic
 * order key, unique per provider over the live rows by V88's
 * {@code uq_provider_services_position}).
 *
 * <p><b>Money in the V2 house shape:</b> integer cents ({@code price_cents})
 * with a 3-letter ISO 4217 currency — never a float ("S7: the monetary
 * shape is complete"). A {@code null} amount means "price not declared"
 * — deliberately distinct from zero: "not declared" is honest absence,
 * never a promise of free.
 *
 * <p><b>Class name:</b> {@code OfferedService} — the table keeps the
 * plan's name {@code provider_services}; the class cannot be
 * {@code ProviderService} because that name is the module's existing
 * CRUD service (the collision is why this javadoc states the mapping).
 */
@Entity
@Table(name = "provider_services")
@Audited
public class OfferedService extends BaseEntity {

    @Id
    private UUID id;

    /** The owning provider profile — the page this row renders on. */
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /** The service's display title (non-blank, 200 — the V88 CHECK). */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** The optional display description (1000 — the bio's length class). */
    @Column(name = "description", length = 1000)
    private String description;

    /**
     * The optional declared duration in whole minutes — positive when
     * present (the V88 CHECK); null means "not declared".
     */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    /** The optional price in integer cents (the V2 money shape — non-negative). */
    @Column(name = "price_cents")
    private Long priceCents;

    /** The ISO 4217 alphabetic currency of {@link #priceCents} — required iff an amount is present. */
    @Column(name = "currency", length = 3)
    private String currency;

    /** The display order key within the provider's menu (non-negative, unique per provider over live rows). */
    @Column(name = "position", nullable = false)
    private int position;

    protected OfferedService() {
    }

    private OfferedService(UUID id, UUID providerId, String title, String description,
                           Integer durationMinutes, Long priceCents, String currency, int position) {
        this.id = id;
        this.providerId = providerId;
        this.title = title;
        this.description = description;
        this.durationMinutes = durationMinutes;
        this.priceCents = priceCents;
        this.currency = currency;
        this.position = position;
    }

    /**
     * Factory for the write surface: validates the money pair's
     * completeness (an amount without a currency, or a currency without
     * an amount, is the V88 CHECK's own defect definition) and the
     * declared duration's positivity — the entity never holds a shape
     * the database would reject.
     */
    public static OfferedService create(UUID providerId, String title, String description,
                                        Integer durationMinutes, Long priceCents, String currency,
                                        int position) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (durationMinutes != null && durationMinutes <= 0) {
            throw new IllegalArgumentException("durationMinutes must be positive when declared");
        }
        if ((priceCents == null) != (currency == null)) {
            throw new IllegalArgumentException("price and currency are declared together or not at all");
        }
        if (priceCents != null && priceCents < 0) {
            throw new IllegalArgumentException("priceCents must be non-negative");
        }
        if (currency != null && !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO 4217 code");
        }
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        return new OfferedService(UUID.randomUUID(), providerId, title.strip(), description,
                durationMinutes, priceCents, currency, position);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public Long getPriceCents() {
        return priceCents;
    }

    public String getCurrency() {
        return currency;
    }

    public int getPosition() {
        return position;
    }

    /**
     * The self-service update (PUT replacement semantics — the profile's
     * own documented contract): every display field replaces; the
     * construction-time validation applies unchanged.
     */
    public void update(String newTitle, String newDescription, Integer newDurationMinutes,
                       Long newPriceCents, String newCurrency) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (newDurationMinutes != null && newDurationMinutes <= 0) {
            throw new IllegalArgumentException("durationMinutes must be positive when declared");
        }
        if ((newPriceCents == null) != (newCurrency == null)) {
            throw new IllegalArgumentException("price and currency are declared together or not at all");
        }
        if (newPriceCents != null && newPriceCents < 0) {
            throw new IllegalArgumentException("priceCents must be non-negative");
        }
        if (newCurrency != null && !newCurrency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO 4217 code");
        }
        this.title = newTitle.strip();
        this.description = newDescription;
        this.durationMinutes = newDurationMinutes;
        this.priceCents = newPriceCents;
        this.currency = newCurrency;
    }

    /**
     * The menu reordering seam: moves the row within the provider's
     * position space (the write surface allocates collision-free positions
     * under the provider's advisory allocation lock — the W1
     * {@code max-based allocation} lesson applied to this list).
     */
    public void moveTo(int newPosition) {
        if (newPosition < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        this.position = newPosition;
    }
}
