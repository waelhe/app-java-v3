package com.marketplace.pricing;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.EffectivePricePort;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class PricingService implements EffectivePricePort {

    private final PricingRuleRepository pricingRuleRepository;
    private final ListingWeekendRuleRepository weekendRuleRepository;
    private final SeasonalRateRepository seasonalRateRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<CurrencyExchangePort> currencyExchange;

    private static final Set<String> PRICING_CACHE_NAMES = Set.of("pricing-calculations");

    /**
     * L26 (feature-expansion roadmap §5): the weekend days — Saturday and
     * Sunday. The roadmap's own numeric example fixes the definition: a
     * Thu→Sun stay with weekend multiplier 1.2 prices Thu and Fri at base
     * and Sat at ×1.2 (Friday is a regular day; the Sunday checkout is
     * never priced — exclusive end).
     */
    private static final Set<DayOfWeek> WEEKEND_DAYS = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    @org.springframework.beans.factory.annotation.Autowired
    public PricingService(PricingRuleRepository pricingRuleRepository,
                          ListingWeekendRuleRepository weekendRuleRepository,
                          SeasonalRateRepository seasonalRateRepository,
                          ApplicationEventPublisher eventPublisher,
                          ObjectProvider<CurrencyExchangePort> currencyExchange) {
        this.pricingRuleRepository = pricingRuleRepository;
        this.weekendRuleRepository = weekendRuleRepository;
        this.seasonalRateRepository = seasonalRateRepository;
        this.eventPublisher = eventPublisher;
        this.currencyExchange = currencyExchange;
    }

    /**
     * Test convenience — the pre-B4 constructor shape: no exchange channel
     * bound (the dormant default), no calendar repositories (the flat
     * pre-L26 computation).
     */
    PricingService(PricingRuleRepository pricingRuleRepository, ApplicationEventPublisher eventPublisher) {
        this(pricingRuleRepository, null, null, eventPublisher, null);
    }

    /**
     * Test convenience — the pre-L26 B4 shape: an exchange channel without
     * the calendar repositories. Kept because {@code
     * PricingServiceConvertTest} pins the dormant/bound channel behaviour
     * through it — L26 extends the service without breaking any existing
     * test seam (the no-layer-breaks rule).
     */
    PricingService(PricingRuleRepository pricingRuleRepository, ApplicationEventPublisher eventPublisher,
                   ObjectProvider<CurrencyExchangePort> currencyExchange) {
        this(pricingRuleRepository, null, null, eventPublisher, currencyExchange);
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
        return calculatePriceInternal(basePriceCents, category);
    }

    private PriceBreakdown calculatePriceInternal(long basePriceCents, String category) {
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

    /**
     * L26 (feature-expansion roadmap §5): the windowed quote — the roadmap's
     * extended contract ("توسيع عقد calculatePrice ليقبل نافذة الإقامة"): the
     * base the tax/discount pipeline sees is the EFFECTIVE price sum of the
     * stay window (day-sliced, precedence rule applied — see
     * {@link #effectiveTotalCents}). The legacy two-arg path stays
     * byte-identical (a flat base, no window) — the acceptance criteria's
     * numeric examples pin THIS method: nights + "الضريبة القائمة".
     *
     * <p>Cache: the same {@code pricing-calculations} namespace, key
     * extended with the listing and the window (server-resolved values —
     * UUID/ISO instants contain no delimiter, the injectivity class the
     * L27 lesson pinned). Calendar writes evict via
     * {@code PRICING_CACHE_NAMES} (roadmap criterion 4).
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "pricing-calculations",
            key = "#listingId + '|' + #basePriceCents + '|' + #category + '|' + #checkIn + '|' + #checkOut")
    @Observed(name = "pricing.calculate.window")
    public PriceBreakdown calculatePrice(UUID listingId, long basePriceCents, String category,
                                         Instant checkIn, Instant checkOut) {
        long effectiveCents = effectiveTotalCents(listingId, basePriceCents, checkIn, checkOut);
        return calculatePriceInternal(effectiveCents, category);
    }

    /**
     * L26: the effective price total of a stay window — the booking-seam
     * number (the flat price's generalization; the tax pipeline belongs to
     * the quote surface only).
     *
     * <p>Activation model (the roadmap's most important criterion —
     * byte-compatibility for a listing with NO rules): a listing with no
     * weekend rule and no seasonal rates answers the FLAT base price —
     * exactly the pre-L26 booking number for every window. The day-sliced
     * model engages only when the host has configured a calendar row.
     *
     * <p>Day slicing: every UTC date {@code d} with
     * {@code checkIn-date <= d < checkOut-date} is one priced night. A
     * same-date window (an intra-day stay, e.g. 10:00→14:00) prices its
     * single check-in date — never zero (a free booking would be a defect,
     * and rejecting the window would break pre-L26 intra-day bookings).
     * A REVERSED window (check-out strictly before check-in) is rejected
     * with 400 BEFORE the repository reads — the SearchCriteria gate
     * convention (a reversed window is never a window, not a degenerate
     * one-night stay; CodeRabbit round 1: the no-calendar fallback used to
     * mask it by answering the flat price).
     */
    @Override
    @Transactional(readOnly = true)
    public long calculateBookingTotalCents(UUID listingId, long basePriceCents,
                                           Instant startsAt, Instant endsAt) {
        return effectiveTotalCents(listingId, basePriceCents, startsAt, endsAt);
    }

    /**
     * The effective per-day sum — package-private seam for the windowed
     * quote and the port implementation (one code path, two surfaces).
     */
    long effectiveTotalCents(UUID listingId, long basePriceCents, Instant checkIn, Instant checkOut) {
        if (checkOut.isBefore(checkIn)) {
            // The window gate (the SearchCriteria convention) — BEFORE any
            // repository read, so a reversed window is 400 on BOTH surfaces
            // (quote and booking seam), flat model or not.
            throw new com.marketplace.shared.api.BadRequestException(
                    "Check-out must not be before check-in");
        }
        ListingWeekendRule weekendRule = weekendRuleRepository == null
                ? null
                : weekendRuleRepository.findByListingId(listingId).orElse(null);
        List<SeasonalRate> rates = seasonalRateRepository == null
                ? List.of()
                : seasonalRateRepository.findByListingIdOrderByFromDateAsc(listingId);
        if (weekendRule == null && rates.isEmpty()) {
            // No calendar — the flat model rides through unchanged.
            return basePriceCents;
        }

        LocalDate from = LocalDate.ofInstant(checkIn, ZoneOffset.UTC);
        LocalDate to = LocalDate.ofInstant(checkOut, ZoneOffset.UTC);
        if (!from.isBefore(to)) {
            // Same-date window — the single check-in date is priced.
            return effectiveNightCents(from, basePriceCents, weekendRule, rates);
        }
        long total = 0;
        for (LocalDate day = from; day.isBefore(to); day = day.plusDays(1)) {
            total += effectiveNightCents(day, basePriceCents, weekendRule, rates);
        }
        return total;
    }

    /**
     * One night's effective price — the precedence rule, exactly as the
     * roadmap states it: a covering seasonal range's ABSOLUTE price
     * replaces the base (the multiplier never stacks on it); outside the
     * ranges the weekend multiplier applies to the base; otherwise base.
     */
    private long effectiveNightCents(LocalDate day, long basePriceCents,
                                     ListingWeekendRule weekendRule, List<SeasonalRate> rates) {
        for (SeasonalRate rate : rates) {
            if (rate.covers(day)) {
                return rate.getPriceCents();
            }
        }
        if (weekendRule != null && WEEKEND_DAYS.contains(day.getDayOfWeek())) {
            return BigDecimal.valueOf(basePriceCents)
                    .multiply(weekendRule.getMultiplier())
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        }
        return basePriceCents;
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
