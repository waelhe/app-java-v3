package com.marketplace.pricing;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "pricing_rules")
public class PricingRule extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "category", length = 100)
    private String category;

    @DecimalMin(value = "0", inclusive = true, message = "Tax rate must be >= 0")
    @DecimalMax(value = "1", inclusive = true, message = "Tax rate must be <= 1")
    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate;

    @DecimalMin(value = "0", inclusive = true, message = "Discount percentage must be >= 0")
    @DecimalMax(value = "1", inclusive = true, message = "Discount percentage must be <= 1")
    @Column(name = "discount_pct", nullable = false, precision = 5, scale = 4)
    private BigDecimal discountPct;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected PricingRule() {
    }

    public PricingRule(UUID id, String name, String category,
                       BigDecimal taxRate, BigDecimal discountPct) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.taxRate = taxRate;
        this.discountPct = discountPct;
    }

    public static PricingRule create(String name, String category,
                                     BigDecimal taxRate, BigDecimal discountPct) {
        return new PricingRule(UUID.randomUUID(), name, category, taxRate, discountPct);
    }

    @Override
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public BigDecimal getTaxRate() { return taxRate; }
    public BigDecimal getDiscountPct() { return discountPct; }
    public boolean isActive() { return active; }

    public void deactivate() { this.active = false; }
    public void activate() { this.active = true; }
}