package com.marketplace.pricing;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@Transactional
public class PricingService {

    private final PricingRuleRepository pricingRuleRepository;

    public PricingService(PricingRuleRepository pricingRuleRepository) {
        this.pricingRuleRepository = pricingRuleRepository;
    }

    /**
     * Calculate the total price for a listing including tax and discount.
     *
     * @param basePriceCents base price in cents
     * @param category       listing category (for category-specific rules)
     * @return PriceBreakdown with subtotal, tax, discount, and total
     */
    @Transactional(readOnly = true)
    public PriceBreakdown calculatePrice(long basePriceCents, String category) {
        PricingRule rule = pricingRuleRepository.findByCategoryAndActiveTrue(category)
                .or(() -> pricingRuleRepository.findFirstByActiveTrueOrderByCreatedAtDesc())
                .orElseGet(() -> defaultRule());

        BigDecimal basePrice = BigDecimal.valueOf(basePriceCents);

        // Discount (discountPct is decimal 0→1, e.g. 0.05 = 5%)
        BigDecimal discountAmount = basePrice.multiply(rule.getDiscountPct())
                .setScale(0, RoundingMode.HALF_UP);
        long discountCents = discountAmount.longValue();

        // Subtotal after discount
        long subtotalCents = basePriceCents - discountCents;

        // Tax on subtotal (taxRate is decimal 0→1, e.g. 0.15 = 15%)
        BigDecimal taxAmount = BigDecimal.valueOf(subtotalCents).multiply(rule.getTaxRate())
                .setScale(0, RoundingMode.HALF_UP);
        long taxCents = taxAmount.longValue();

        long totalCents = subtotalCents + taxCents;

        return new PriceBreakdown(basePriceCents, discountCents, subtotalCents, taxCents, totalCents,
                rule.getTaxRate(), rule.getDiscountPct());
    }

    private PricingRule defaultRule() {
        return PricingRule.create("Default", null,
                new BigDecimal("0.1500"), BigDecimal.ZERO);
    }

    public record PriceBreakdown(
            long basePriceCents,
            long discountCents,
            long subtotalCents,
            long taxCents,
            long totalCents,
            BigDecimal taxRate,
            BigDecimal discountPct
    ) {}
}