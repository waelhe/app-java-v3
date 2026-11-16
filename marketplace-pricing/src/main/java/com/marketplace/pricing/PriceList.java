package com.marketplace.pricing;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * The Saleor fit-gap study's Phase 3 (PR #602 §7-Phase-3): one price list
 * — a currency-scoped, window-bounded set of unit-price OVERRIDES the
 * placement resolves before the catalog's own authoritative price. The
 * Saleor price-list behavior adopted: the list does not REPLACE the
 * catalog (the study's «keep a single source of truth»); it layers on top
 * of it, and only its ACTIVE, in-window rows participate.
 *
 * <p><b>Window semantics:</b> {@code [startsAt, endsAt)} with NULL ends =
 * open-ended (the seasonal-rate interval convention). A list whose window
 * does not cover the placement instant is invisible to resolution — never
 * an error, the catalog price stands.
 */
@Entity
@Table(name = "price_lists")
@Audited
public class PriceList extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "starts_at")
    private Instant startsAt;

    /** Exclusive end — null means open-ended. */
    @Column(name = "ends_at")
    private Instant endsAt;

    protected PriceList() {
    }

    private PriceList(UUID id, String name, String currency, boolean active,
                      Instant startsAt, Instant endsAt) {
        this.id = id;
        this.name = name;
        this.currency = currency;
        this.active = active;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public static PriceList of(String name, String currency, boolean active,
                               Instant startsAt, Instant endsAt) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A price list requires a name");
        }
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("A price list window requires startsAt before endsAt");
        }
        return new PriceList(UUID.randomUUID(), name.strip(),
                currency.strip().toUpperCase(java.util.Locale.ROOT), active, startsAt, endsAt);
    }

    /** The window covers the instant (null bounds are open). */
    public boolean covers(Instant at) {
        if (startsAt != null && at.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || at.isBefore(endsAt);
    }

    public void rename(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("A price list requires a name");
        }
        this.name = newName.strip();
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }
}
