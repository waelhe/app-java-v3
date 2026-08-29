package com.marketplace.pricing;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class PricingService {

    private final PricingRuleRepository pricingRuleRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final Set<String> PRICING_CACHE_NAMES = Set.of("pricing-calculations");

    public PricingService(PricingRuleRepository pricingRuleRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.pricingRuleRepository = pricingRuleRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Calculate the total price for a listing including tax and discount.
     *
     * @param basePriceCents base price in cents
     * @param category       listing category (for category-specific rules)
     * @return PriceBreakdown with subtotal, tax, discount, and total
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "pricing-calculations", key = "#basePriceCents + '-' + #category")
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

    @Transactional(readOnly = true)
    public List<PricingRule> listRules() {
        return pricingRuleRepository.findAll();
    }

    public PricingRule createRule(String name, String category,
                                  BigDecimal taxRate, BigDecimal discountPct) {
        PricingRule rule = PricingRule.create(name, category, taxRate, discountPct);
        PricingRule saved = pricingRuleRepository.save(rule);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<PricingRule> findById(UUID id) {
        return pricingRuleRepository.findById(id);
    }

    @Transactional
    public PricingRule activate(UUID id) {
        PricingRule rule = pricingRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PricingRule", id));
        rule.activate();
        PricingRule saved = pricingRuleRepository.save(rule);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
        return saved;
    }

    @Transactional
    public PricingRule deactivate(UUID id) {
        PricingRule rule = pricingRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PricingRule", id));
        rule.deactivate();
        PricingRule saved = pricingRuleRepository.save(rule);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
        return saved;
    }

    @Transactional
    public void deleteById(UUID id) {
        if (!pricingRuleRepository.existsById(id)) {
            throw new ResourceNotFoundException("PricingRule", id);
        }
        pricingRuleRepository.deleteById(id);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
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
