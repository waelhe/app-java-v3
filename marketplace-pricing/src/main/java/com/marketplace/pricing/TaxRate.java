package com.marketplace.pricing;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * The Saleor fit-gap study's Phase 3: one jurisdiction's active tax rate —
 * the EXPLICIT tax policy the study demands («tax calculation must be
 * explicit and frozen at order placement», §5). One ACTIVE row per
 * country (the partial unique index); superseding a rate is a NEW row +
 * deactivation of the old — history is never edited.
 *
 * <p><b>Modes (both explicit, both frozen at placement):</b>
 * INCLUSIVE — the jurisdiction's displayed prices already contain the tax
 * (the KSA B2C posture); the order's tax component is EXTRACTED from the
 * agreed amount, the total stays the discounted subtotal. EXCLUSIVE — the
 * tax is ADDED on top of the discounted subtotal.
 */
@Entity
@Table(name = "tax_rates")
@Audited
public class TaxRate extends BaseEntity {

    public enum TaxMode { INCLUSIVE, EXCLUSIVE }

    @Id
    private UUID id;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "rate_bps", nullable = false)
    private int rateBps;

    @Column(name = "tax_mode", nullable = false, length = 12)
    private TaxMode taxMode;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected TaxRate() {
    }

    private TaxRate(UUID id, String countryCode, String name, int rateBps, TaxMode taxMode, boolean active) {
        this.id = id;
        this.countryCode = countryCode;
        this.name = name;
        this.rateBps = rateBps;
        this.taxMode = taxMode;
        this.active = active;
    }

    public static TaxRate of(String countryCode, String name, int rateBps, TaxMode taxMode, boolean active) {
        String normalized = countryCode == null ? "" : countryCode.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("A tax rate's country is a 2-letter ISO code");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A tax rate requires a name");
        }
        if (rateBps < 0 || rateBps > 10000) {
            throw new IllegalArgumentException("A tax rate carries 0..10000 basis points");
        }
        if (taxMode == null) {
            throw new IllegalArgumentException("A tax rate requires a mode");
        }
        return new TaxRate(UUID.randomUUID(), normalized, name.strip(), rateBps, taxMode, active);
    }

    /**
     * INCLUSIVE: the component contained in {@code amount} —
     * {@code amount × rate / (1 + rate)} rounded half-up. EXCLUSIVE: the
     * component added on top — {@code amount × rate}. Both are the frozen
     * facts the order's breakdown records. The percentage arithmetic is
     * exact integer quotient/remainder ({@link ExactPercentMath}) — the
     * double division drifted on large amounts (the review's measured
     * example: one minor unit off at {@code 1099511641251 × 8749 bps}).
     */
    public long taxOn(long amountMinor) {
        if (taxMode == TaxMode.INCLUSIVE) {
            return ExactPercentMath.roundHalfUpScaled(amountMinor, rateBps, 10000L + rateBps);
        }
        return ExactPercentMath.roundHalfUpScaled(amountMinor, rateBps, 10000L);
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

    public String getCountryCode() {
        return countryCode;
    }

    public String getName() {
        return name;
    }

    public int getRateBps() {
        return rateBps;
    }

    public TaxMode getTaxMode() {
        return taxMode;
    }

    public boolean isActive() {
        return active;
    }
}
