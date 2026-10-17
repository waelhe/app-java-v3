package com.marketplace.pricing;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CommercialPricingPort;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ProductPricingPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The Saleor fit-gap study's Phase 3 engine (PR #602 §7-Phase-3): the
 * commercial rules — price lists, promotions, vouchers, taxes — resolved
 * as EXPLICIT policies for one order at one instant.
 *
 * <p><b>Resolution law (each step grounded in the study):</b>
 * <ol>
 *   <li>Unit price: the ACTIVE in-window price list whose currency
 *       matches supplies the entry override (exact variant entry first,
 *       then the product-level entry); otherwise the catalog's own
 *       authoritative price through {@link ProductPricingPort} — the
 *       catalog keeps owning identity and base price (§6: «keep a single
 *       source of truth for each capability»).</li>
 *   <li>Promotion: the BEST single applicable promotion (max discount on
 *       the subtotal — ties broken deterministically by (createdAt, id));
 *       promotions never stack with each other (the explicit stacking
 *       rule).</li>
 *   <li>Voucher: at most one, validated read-only in
 *       {@link #computeBreakdown} and redeemed under the voucher row's
 *       pessimistic lock in {@link #settleVoucher} (inside the placement
 *       transaction — a refused redemption rolls the placement back).</li>
 *   <li>Tax: the jurisdiction's ACTIVE rate computes the component on the
 *       DISCOUNTED subtotal; INCLUSIVE extracts (total unchanged),
 *       EXCLUSIVE adds (total grows). Either way the amount is frozen on
 *       the order at placement (§5: «tax calculation must be explicit and
 *       frozen at order placement»).</li>
 * </ol>
 */
@Service
public class CommercialPricingService implements CommercialPricingPort {

    private static final Logger log = LoggerFactory.getLogger(CommercialPricingService.class);

    private final PriceListRepository priceLists;
    private final PriceListEntryRepository priceListEntries;
    private final PromotionRepository promotions;
    private final VoucherRepository vouchers;
    private final VoucherRedemptionRepository redemptions;
    private final TaxRateRepository taxRates;
    private final ProductPricingPort productPricingPort;
    private final CommercialPricingProperties properties;

    public CommercialPricingService(PriceListRepository priceLists,
                                    PriceListEntryRepository priceListEntries,
                                    PromotionRepository promotions,
                                    VoucherRepository vouchers,
                                    VoucherRedemptionRepository redemptions,
                                    TaxRateRepository taxRates,
                                    ProductPricingPort productPricingPort,
                                    CommercialPricingProperties properties) {
        this.priceLists = priceLists;
        this.priceListEntries = priceListEntries;
        this.promotions = promotions;
        this.vouchers = vouchers;
        this.redemptions = redemptions;
        this.taxRates = taxRates;
        this.productPricingPort = productPricingPort;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // Unit-price resolution (the price-list layer over the catalog's own)
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ResolvedUnitPrice resolveUnitPrice(UUID productId, UUID variantId) {
        // The catalog's own record first: it owns identity, shelf states and
        // the base price — the override can only apply ON TOP of a
        // purchasable unit (a suspended product hides its price list too:
        // Saleor's publication rule, the ADR-0012 both-shelves gate).
        final ProductPricingPort.StorefrontState productStorefront;
        final ProductPricingPort.StorefrontState variantStorefront;
        final UUID providerId;
        final long basePriceMinor;
        final String currency;
        if (variantId != null) {
            ProductPricingPort.VariantPrice variant = productPricingPort.priceOfVariant(variantId);
            if (!variant.productId().equals(productId)) {
                throw new BadRequestException("Variant " + variantId + " belongs to product "
                        + variant.productId() + ", not " + productId);
            }
            productStorefront = variant.productStorefront();
            variantStorefront = variant.variantStorefront();
            providerId = variant.providerId();
            basePriceMinor = variant.priceMinor();
            currency = variant.currency();
        } else {
            ProductPricingPort.ProductPrice price = productPricingPort.priceOf(productId);
            productStorefront = price.storefront();
            variantStorefront = null;
            providerId = price.providerId();
            basePriceMinor = price.priceMinor();
            currency = price.currency();
        }
        boolean purchasable = productStorefront == ProductPricingPort.StorefrontState.ACTIVE
                && (variantStorefront == null
                    || variantStorefront == ProductPricingPort.StorefrontState.ACTIVE);
        String refusal = purchasable ? null
                : "product is " + productStorefront
                  + (variantStorefront != null ? ", variant is " + variantStorefront : "");

        // The override: the FIRST active in-window list in the currency
        // whose entry matches the exact unit wins (first-seen list order —
        // the same deterministic total order the cart's lines read in).
        if (purchasable) {
            Instant now = Instant.now();
            List<PriceList> covering = priceLists.findActiveCovering(currency, now);
            if (!covering.isEmpty()) {
                List<UUID> listIds = covering.stream().map(PriceList::getId).toList();
                PriceListEntry override = priceListEntries
                        .findByProductIdAndPriceListIdIn(productId, listIds).stream()
                        .filter(e -> e.matches(productId, variantId))
                        .min(Comparator.comparing((PriceListEntry e) -> listIds.indexOf(e.getPriceListId()))
                                .thenComparing(PriceListEntry::getCreatedAt)
                                .thenComparing(PriceListEntry::getId))
                        .orElse(null);
                if (override != null) {
                    return new ResolvedUnitPrice(override.getAmountMinor(), currency, providerId,
                            true, null, override.getPriceListId());
                }
            }
        }
        return new ResolvedUnitPrice(basePriceMinor, currency, providerId, purchasable,
                refusal, null);
    }

    // ------------------------------------------------------------------
    // The order breakdown (promotion → voucher → tax, the stacking law)
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public CommercialBreakdown computeBreakdown(UUID consumerId, long subtotalMinor,
                                                String currency, String voucherCode) {
        if (subtotalMinor < 0) {
            throw new ConflictException("A breakdown requires a non-negative subtotal");
        }
        Instant now = Instant.now();

        // 1 — the best single applicable promotion (no promotion stacking).
        Promotion best = null;
        long bestDiscount = 0;
        for (Promotion candidate : promotions.findActiveCovering(now)) {
            if (!candidate.applicableTo(subtotalMinor, now)) {
                continue;
            }
            long discount = candidate.discountOn(subtotalMinor);
            if (best == null || discount > bestDiscount) {
                best = candidate;
                bestDiscount = discount;
            }
        }

        // 2 — the voucher on the post-promotion subtotal (read-only
        // validation; the locked redemption is settleVoucher's half).
        long afterPromotion = subtotalMinor - bestDiscount;
        Voucher voucher = null;
        long voucherDiscount = 0;
        if (voucherCode != null && !voucherCode.isBlank()) {
            String normalized = voucherCode.strip().toUpperCase(java.util.Locale.ROOT);
            voucher = vouchers.findByCode(normalized).orElse(null);
            if (voucher == null) {
                throw new ConflictException("Voucher code is not recognized: " + normalized);
            }
            if (!voucher.isActive() || !voucher.covers(now)) {
                throw new ConflictException("Voucher " + normalized
                        + " is not active for this moment");
            }
            if (afterPromotion < voucher.getMinSpendMinor()) {
                throw new ConflictException("Voucher " + normalized + " requires a minimum spend of "
                        + voucher.getMinSpendMinor() + " — the order's discounted subtotal is "
                        + afterPromotion);
            }
            if (voucher.getMaxRedemptions() != null
                    && redemptions.countByVoucherId(voucher.getId()) >= voucher.getMaxRedemptions()) {
                throw new ConflictException("Voucher " + normalized + " has reached its redemption limit");
            }
            if (voucher.getMaxRedemptionsPerUser() != null && consumerId != null
                    && redemptions.countByVoucherIdAndConsumerId(voucher.getId(), consumerId)
                        >= voucher.getMaxRedemptionsPerUser()) {
                throw new ConflictException("Voucher " + normalized
                        + " has reached its per-user redemption limit");
            }
            voucherDiscount = voucher.discountOn(afterPromotion);
        }

        // 3 — the jurisdiction's active tax rate on the discounted subtotal.
        long discounted = afterPromotion - voucherDiscount;
        TaxRate rate = taxRates
                .findByCountryCodeAndActiveTrue(properties.defaultTaxCountry())
                .orElse(null);
        long taxMinor = rate == null ? 0 : rate.taxOn(discounted);
        boolean inclusive = rate != null && rate.getTaxMode() == TaxRate.TaxMode.INCLUSIVE;
        long totalMinor = inclusive ? discounted : discounted + taxMinor;

        return new CommercialBreakdown(
                subtotalMinor,
                bestDiscount, best == null ? null : best.getId(),
                best == null ? null : best.getName(),
                voucherDiscount, voucher == null ? null : voucher.getId(),
                voucher == null ? null : voucher.getCode(),
                taxMinor, rate == null ? null : rate.getCountryCode(), inclusive,
                rate == null ? 0 : rate.getRateBps(),
                totalMinor);
    }

    // ------------------------------------------------------------------
    // The voucher settlement (the locked WRITE inside the placement)
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public void settleVoucher(UUID orderId, UUID consumerId, String voucherCode,
                              long discountMinor, String currency) {
        if (voucherCode == null || voucherCode.isBlank()) {
            return;
        }
        String normalized = voucherCode.strip().toUpperCase(java.util.Locale.ROOT);
        Voucher voucher = vouchers.findByCodeForUpdate(normalized)
                .orElseThrow(() -> new ConflictException("Voucher code is not recognized: " + normalized));
        // The idempotency law first: a replayed settle for the SAME order is
        // a logged no-op (the partial unique index is the structural backstop).
        if (redemptions.findByVoucherIdAndOrderId(voucher.getId(), orderId).isPresent()) {
            log.info("Voucher {} already redeemed by order {} — the settle replay is a no-op",
                    normalized, orderId);
            return;
        }
        Instant now = Instant.now();
        if (!voucher.isActive() || !voucher.covers(now)) {
            throw new ConflictException("Voucher " + normalized + " is no longer active");
        }
        if (voucher.getMaxRedemptions() != null
                && redemptions.countByVoucherId(voucher.getId()) >= voucher.getMaxRedemptions()) {
            throw new ConflictException("Voucher " + normalized + " has reached its redemption limit");
        }
        if (voucher.getMaxRedemptionsPerUser() != null
                && redemptions.countByVoucherIdAndConsumerId(voucher.getId(), consumerId)
                    >= voucher.getMaxRedemptionsPerUser()) {
            throw new ConflictException("Voucher " + normalized
                    + " has reached its per-user redemption limit");
        }
        redemptions.save(VoucherRedemption.of(voucher.getId(), orderId, consumerId,
                discountMinor, currency, now));
        log.info("Voucher {} redeemed by order {} — {} {} recorded under the voucher row's lock",
                normalized, orderId, discountMinor, currency);
    }

    // ------------------------------------------------------------------
    // The operator's policy management surface (ADMIN-gated at the controller)
    // ------------------------------------------------------------------

    @Transactional
    public PriceList createPriceList(String name, String currency, Boolean active,
                                     Instant startsAt, Instant endsAt) {
        return priceLists.save(PriceList.of(name, currency,
                active == null || active, startsAt, endsAt));
    }

    @Transactional(readOnly = true)
    public List<PriceList> listPriceLists() {
        return priceLists.findAll();
    }

    @Transactional
    public PriceListEntry addPriceListEntry(UUID priceListId, UUID productId, UUID variantId,
                                            long amountMinor) {
        PriceList list = priceLists.findById(priceListId)
                .orElseThrow(() -> new ResourceNotFoundException("PriceList", priceListId));
        return priceListEntries.save(
                PriceListEntry.of(list.getId(), productId, variantId, amountMinor));
    }

    @Transactional(readOnly = true)
    public List<PriceListEntry> listPriceListEntries(UUID priceListId) {
        return priceListEntries.findByPriceListId(priceListId);
    }

    @Transactional
    public Promotion createPromotion(String name, Promotion.DiscountType type, long value,
                                     Long minSpendMinor, Instant startsAt, Instant endsAt,
                                     Boolean active) {
        return promotions.save(Promotion.of(name, type, value,
                minSpendMinor == null ? 0 : minSpendMinor, startsAt, endsAt,
                active == null || active));
    }

    @Transactional(readOnly = true)
    public List<Promotion> listPromotions() {
        return promotions.findAll();
    }

    @Transactional
    public Voucher createVoucher(String code, String name, Voucher.DiscountType type, long value,
                                 Long minSpendMinor, Instant startsAt, Instant endsAt,
                                 Long maxRedemptions, Long maxPerUser, Boolean active) {
        return vouchers.save(Voucher.of(code, name, type, value,
                minSpendMinor == null ? 0 : minSpendMinor, startsAt, endsAt,
                maxRedemptions, maxPerUser, active == null || active));
    }

    @Transactional(readOnly = true)
    public List<Voucher> listVouchers() {
        return vouchers.findAll();
    }

    @Transactional
    public TaxRate createTaxRate(String countryCode, String name, int rateBps,
                                 TaxRate.TaxMode mode, Boolean active) {
        boolean activate = active == null || active;
        if (activate) {
            // one ACTIVE row per country: superseding deactivates the old —
            // history is never edited (the partial unique index's law)
            taxRates.findByCountryCodeAndActiveTrue(countryCode).ifPresent(TaxRate::deactivate);
        }
        return taxRates.save(TaxRate.of(countryCode, name, rateBps, mode, activate));
    }

    @Transactional(readOnly = true)
    public List<TaxRate> listTaxRates() {
        return taxRates.findAll();
    }
}
