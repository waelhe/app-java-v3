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
 * The Saleor fit-gap study's Phase 3: one order-level promotion — an
 * explicit, window-bounded discount policy. The study's stacking question
 * is answered by construction: the resolution picks the BEST single
 * applicable promotion per order ({@code CommercialPricingService});
 * promotions never stack with each other.
 *
 * <p><b>Value semantics (the CHECK contract):</b> PERCENTAGE carries basis
 * points (1..10000 = 0.01%..100%); FIXED carries minor units. The
 * discount applies on the order's subtotal when the subtotal meets
 * {@code minSpendMinor}, and CAPS at the subtotal (a discount can never
 * turn an order negative).
 */
@Entity
@Table(name = "promotions")
@Audited
public class Promotion extends BaseEntity {

    /** The closed vocabulary (the DB CHECK's membership set). */
    public enum DiscountType { PERCENTAGE, FIXED }

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "discount_type", nullable = false, length = 12)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    @Column(name = "min_spend_minor", nullable = false)
    private long minSpendMinor;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Promotion() {
    }

    private Promotion(UUID id, String name, DiscountType discountType, long discountValue,
                      long minSpendMinor, Instant startsAt, Instant endsAt, boolean active) {
        this.id = id;
        this.name = name;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minSpendMinor = minSpendMinor;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.active = active;
    }

    public static Promotion of(String name, DiscountType discountType, long discountValue,
                               long minSpendMinor, Instant startsAt, Instant endsAt, boolean active) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A promotion requires a name");
        }
        if (discountType == null) {
            throw new IllegalArgumentException("A promotion requires a discount type");
        }
        if (discountType == DiscountType.PERCENTAGE && (discountValue <= 0 || discountValue > 10000)) {
            throw new IllegalArgumentException("A percentage promotion carries 1..10000 basis points");
        }
        if (discountType == DiscountType.FIXED && discountValue <= 0) {
            throw new IllegalArgumentException("A fixed promotion carries a positive minor amount");
        }
        if (minSpendMinor < 0) {
            throw new IllegalArgumentException("A promotion's minimum spend cannot be negative");
        }
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("A promotion window requires startsAt before endsAt");
        }
        return new Promotion(UUID.randomUUID(), name.strip(), discountType, discountValue,
                minSpendMinor, startsAt, endsAt, active);
    }

    /** Eligibility at an instant: active, in window, and the spend meets the minimum. */
    public boolean applicableTo(long subtotalMinor, Instant at) {
        return active && covers(at) && subtotalMinor >= minSpendMinor;
    }

    public boolean covers(Instant at) {
        if (startsAt != null && at.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || at.isBefore(endsAt);
    }

    /** The discount on the subtotal, capped at the subtotal. */
    public long discountOn(long subtotalMinor) {
        long raw = discountType == DiscountType.PERCENTAGE
                ? Math.round(subtotalMinor * discountValue / 10000.0)
                : discountValue;
        return Math.min(raw, subtotalMinor);
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

    public DiscountType getDiscountType() {
        return discountType;
    }

    public long getDiscountValue() {
        return discountValue;
    }

    public long getMinSpendMinor() {
        return minSpendMinor;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public boolean isActive() {
        return active;
    }
}
