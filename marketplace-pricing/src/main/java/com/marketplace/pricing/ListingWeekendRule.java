package com.marketplace.pricing;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * L26 (feature-expansion roadmap §5): the weekend rule of one listing's
 * price calendar — a multiplier applied to the listing's base price on
 * weekend days (Saturday and Sunday — the roadmap's own numeric example:
 * a Thu→Sun stay prices Thu and Fri at base and Sat at ×multiplier). One
 * LIVE row per listing — enforced by the partial unique index
 * {@code uq_listing_weekend_rules_live_listing} (V41: UNIQUE over
 * {@code WHERE is_deleted = FALSE} only, so a soft-deleted row never
 * blocks re-creation — the @SoftDelete interplay documented there); the
 * mapping deliberately declares no unique=true because the schema truth
 * is that partial index, not a table constraint. The multiplier never
 * stacks on a covering seasonal range's absolute price (the precedence
 * rule lives in {@code PricingService.effectiveTotalCents}).
 */
@Entity
@Table(name = "listing_weekend_rules")
@Audited
public class ListingWeekendRule extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    /**
     * The multiplier on the base price (e.g. 1.20 = +20%). Decimal 3-digit
     * scale (V41 NUMERIC(6,3)); must be strictly positive.
     */
    @DecimalMin(value = "0", inclusive = false, message = "Weekend multiplier must be > 0")
    @DecimalMax(value = "10", inclusive = true, message = "Weekend multiplier must be <= 10")
    @Column(name = "multiplier", nullable = false, precision = 6, scale = 3)
    private BigDecimal multiplier;

    protected ListingWeekendRule() {
    }

    private ListingWeekendRule(UUID id, UUID listingId, BigDecimal multiplier) {
        this.id = id;
        this.listingId = listingId;
        this.multiplier = multiplier;
    }

    /**
     * @throws IllegalArgumentException when the listing id or multiplier is
     *                                  null, or the multiplier is outside
     *                                  {@code (0, 10]} — the callers map it
     *                                  to the 400 taxonomy
     */
    public static ListingWeekendRule create(UUID listingId, BigDecimal multiplier) {
        validateMultiplier(multiplier);
        if (listingId == null) {
            throw new IllegalArgumentException("Weekend rule requires a listing id");
        }
        return new ListingWeekendRule(UUID.randomUUID(), listingId, multiplier);
    }

    void changeMultiplier(BigDecimal multiplier) {
        validateMultiplier(multiplier);
        this.multiplier = multiplier;
    }

    /** Same bounds as the entity bean-validation annotations — checked eagerly in the factory. */
    private static void validateMultiplier(BigDecimal multiplier) {
        if (multiplier == null) {
            throw new IllegalArgumentException("Weekend multiplier is required");
        }
        if (multiplier.signum() <= 0 || multiplier.compareTo(MAX_MULTIPLIER) > 0) {
            throw new IllegalArgumentException("Weekend multiplier must be > 0 and <= 10");
        }
    }

    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("10");

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getListingId() {
        return listingId;
    }

    public BigDecimal getMultiplier() {
        return multiplier;
    }
}
