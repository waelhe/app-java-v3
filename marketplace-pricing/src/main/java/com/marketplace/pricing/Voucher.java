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
 * The Saleor fit-gap study's Phase 3: one voucher (coupon) — a CODE-keyed
 * discount with usage limits, redeemed at most once per order (the
 * {@code uq_voucher_redemptions_voucher_order} unique index is the
 * idempotency law) and bounded globally and per consumer.
 *
 * <p>The code is stored NORMALIZED (trimmed + upper-cased — the V223 CHECK
 * pins it) so the buyer's casing never forks the lookup. The redemption
 * accounting is COUNTED from the redemption rows under the voucher row's
 * pessimistic lock at settle time — never a read-modify-written counter
 * (the house {@code findByIdForUpdate} discipline).
 */
@Entity
@Table(name = "vouchers")
@Audited
public class Voucher extends BaseEntity {

    public enum DiscountType { PERCENTAGE, FIXED }

    @Id
    private UUID id;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", length = 120)
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

    @Column(name = "max_redemptions")
    private Long maxRedemptions;

    @Column(name = "max_redemptions_per_user")
    private Long maxRedemptionsPerUser;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Voucher() {
    }

    private Voucher(UUID id, String code, String name, DiscountType discountType, long discountValue,
                    long minSpendMinor, Instant startsAt, Instant endsAt,
                    Long maxRedemptions, Long maxRedemptionsPerUser, boolean active) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minSpendMinor = minSpendMinor;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.maxRedemptions = maxRedemptions;
        this.maxRedemptionsPerUser = maxRedemptionsPerUser;
        this.active = active;
    }

    public static Voucher of(String code, String name, DiscountType discountType, long discountValue,
                             long minSpendMinor, Instant startsAt, Instant endsAt,
                             Long maxRedemptions, Long maxRedemptionsPerUser, boolean active) {
        String normalized = code == null ? "" : code.strip().toUpperCase(java.util.Locale.ROOT);
        if (normalized.length() < 4 || normalized.length() > 64) {
            throw new IllegalArgumentException("A voucher code is 4..64 characters");
        }
        if (!normalized.matches("[A-Z0-9-]+")) {
            throw new IllegalArgumentException("A voucher code is uppercase letters, digits and dashes");
        }
        if (discountType == null) {
            throw new IllegalArgumentException("A voucher requires a discount type");
        }
        if (discountType == DiscountType.PERCENTAGE && (discountValue <= 0 || discountValue > 10000)) {
            throw new IllegalArgumentException("A percentage voucher carries 1..10000 basis points");
        }
        if (discountType == DiscountType.FIXED && discountValue <= 0) {
            throw new IllegalArgumentException("A fixed voucher carries a positive minor amount");
        }
        if (minSpendMinor < 0) {
            throw new IllegalArgumentException("A voucher's minimum spend cannot be negative");
        }
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("A voucher window requires startsAt before endsAt");
        }
        if (maxRedemptions != null && maxRedemptions <= 0) {
            throw new IllegalArgumentException("A voucher's redemption limit is positive or null");
        }
        if (maxRedemptionsPerUser != null && maxRedemptionsPerUser <= 0) {
            throw new IllegalArgumentException("A voucher's per-user limit is positive or null");
        }
        return new Voucher(UUID.randomUUID(), normalized,
                name == null ? null : name.strip(), discountType, discountValue, minSpendMinor,
                startsAt, endsAt, maxRedemptions, maxRedemptionsPerUser, active);
    }

    public boolean covers(Instant at) {
        if (startsAt != null && at.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || at.isBefore(endsAt);
    }

    /** The discount on the given amount, capped at that amount. */
    public long discountOn(long amountMinor) {
        long raw = discountType == DiscountType.PERCENTAGE
                ? Math.round(amountMinor * discountValue / 10000.0)
                : discountValue;
        return Math.min(raw, amountMinor);
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

    public String getCode() {
        return code;
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

    public Long getMaxRedemptions() {
        return maxRedemptions;
    }

    public Long getMaxRedemptionsPerUser() {
        return maxRedemptionsPerUser;
    }

    public boolean isActive() {
        return active;
    }
}
