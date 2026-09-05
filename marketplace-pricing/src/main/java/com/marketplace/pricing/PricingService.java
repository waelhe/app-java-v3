package com.marketplace.pricing;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import io.micrometer.observation.annotation.Observed;
import java.io.Serializable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class PricingService {

    private final PricingRuleRepository pricingRuleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<CurrencyExchangePort> currencyExchange;

    private static final Set<String> PRICING_CACHE_NAMES = Set.of("pricing-calculations");

    @org.springframework.beans.factory.annotation.Autowired
    public PricingService(PricingRuleRepository pricingRuleRepository,
                          ApplicationEventPublisher eventPublisher,
                          ObjectProvider<CurrencyExchangePort> currencyExchange) {
        this.pricingRuleRepository = pricingRuleRepository;
        this.eventPublisher = eventPublisher;
        this.currencyExchange = currencyExchange;
    }

    /**
     * Test convenience — the pre-B4 constructor shape: no exchange channel
     * bound (the dormant default).
     */
    PricingService(PricingRuleRepository pricingRuleRepository, ApplicationEventPublisher eventPublisher) {
        this(pricingRuleRepository, eventPublisher, null);
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
    @Observed(name = "pricing.calculate")
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

    /**
     * Converts a minor-unit ISO 4217 amount through the bound exchange
     * channel (roadmap B4 / gap G-PROD-4). When no channel is bound (no
     * static rates in configuration) the capability is OFF, not broken:
     * 503 SU-001 with the exact binding recipe — the same dormant-goods
     * contract as MAIL, MEDIA_S3 and PAYMENTS_STRIPE.
     */
    @Transactional(readOnly = true)
    @Observed(name = "pricing.currency.convert")
    public CurrencyExchangePort.ExchangeQuote convert(long amountMinorUnits, String fromCode, String toCode) {
        // Input validation precedes the capability check: a malformed
        // currency code is 400 regardless of whether the channel is bound.
        Currency from = parseCurrency(fromCode);
        Currency to = parseCurrency(toCode);

        CurrencyExchangePort channel = currencyExchange == null ? null : currencyExchange.getIfAvailable();
        if (channel == null) {
            throw new ServiceUnavailableException("Currency exchange channel is not configured. "
                    + "Bind at least one rate via marketplace.pricing.currency.exchange.rates.<CODE> "
                    + "(units of the base currency per 1 unit of CODE; the base defaults to SAR) "
                    + "to enable conversion.");
        }
        return channel.convert(amountMinorUnits, from, to);
    }

    private static Currency parseCurrency(String code) {
        if (code == null || code.isBlank()) {
            throw new com.marketplace.shared.api.BadRequestException(
                    "Currency code is required (ISO 4217, e.g. SAR, USD)");
        }
        try {
            return Currency.getInstance(
                    com.marketplace.shared.api.Currencies.normalize(code));
        } catch (IllegalArgumentException ex) {
            throw new com.marketplace.shared.api.BadRequestException(
                    "Not a valid ISO 4217 currency code: " + code);
        }
    }

    @Transactional(readOnly = true)
    public List<PricingRule> listRules() {
        return pricingRuleRepository.findAll();
    }

    @Observed(name = "pricing.rule.create")
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
    @Observed(name = "pricing.rule.activate")
    public PricingRule activate(UUID id) {
        PricingRule rule = pricingRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PricingRule", id));
        rule.activate();
        PricingRule saved = pricingRuleRepository.save(rule);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
        return saved;
    }

    @Transactional
    @Observed(name = "pricing.rule.deactivate")
    public PricingRule deactivate(UUID id) {
        PricingRule rule = pricingRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PricingRule", id));
        rule.deactivate();
        PricingRule saved = pricingRuleRepository.save(rule);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
        return saved;
    }

    @Transactional
    @Observed(name = "pricing.rule.delete")
    public void deleteById(UUID id) {
        if (!pricingRuleRepository.existsById(id)) {
            throw new ResourceNotFoundException("PricingRule", id);
        }
        pricingRuleRepository.deleteById(id);
        eventPublisher.publishEvent(new CacheInvalidationRequested(PRICING_CACHE_NAMES));
    }

    /**
     * Serializable for the Redis cache value path: the
     * {@code pricing-calculations} @Cacheable site stores instances of this
     * record and Spring Boot's default Redis value serializer is
     * {@code JdkSerializationRedisSerializer} — record classes serialize via
     * their canonical constructor (Object Serialization Specification chapter
     * 4; serialVersionUID defaults to 0L and the match requirement is waived
     * for records). All components (long, BigDecimal) are Serializable.
     */
    public record PriceBreakdown(
            long basePriceCents,
            long discountCents,
            long subtotalCents,
            long taxCents,
            long totalCents,
            BigDecimal taxRate,
            BigDecimal discountPct
    ) implements Serializable {}
}
