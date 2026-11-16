package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The Saleor fit-gap study's Phase 3 (PR #602 §7-Phase-3): the commercial
 * rules — price lists, promotions, vouchers and taxes — are EXPLICIT
 * business policies owned by the pricing module, consumed by the orders
 * module's placement through this shared contract (the V32/V48/V52
 * port discipline verbatim: the orders module never imports the pricing
 * module).
 *
 * <p><b>The resolution law (one authority per fact):</b> the catalog stays
 * the owner of the product/variant IDENTITY and its base price
 * ({@link ProductPricingPort} — untouched); the pricing module LAYERS the
 * commercial policies on top: an active price-list entry OVERRIDES the
 * catalog's unit price, an order-level promotion and (at most) one voucher
 * discount reduce the subtotal under the documented stacking rule, and the
 * jurisdiction's active tax rate computes the tax component. The order
 * FREEZES the whole breakdown at placement (§3.4 of the study: the order
 * is the durable agreement — historical orders are never silently
 * rewritten when a policy changes).
 *
 * <p><b>Stacking rule (explicit, per the study's «define eligibility,
 * duration, limits, scope, concurrency, and stacking/exclusion behavior
 * before implementing a rule engine»):</b> at most ONE promotion (the best
 * applicable one — promotions never stack with each other) and at most
 * ONE voucher per order; the voucher evaluates on the post-promotion
 * subtotal; discounts cap at the subtotal (a discount can never turn an
 * order negative); tax computes on the discounted subtotal.
 */
public interface CommercialPricingPort {

    /**
     * Resolves a purchasable unit's EFFECTIVE unit price for the placement
     * instant: the active price list's entry override when one matches
     * (exact variant entry first, then the product-level entry — the same
     * precedence the cart's union key already implies), otherwise the
     * catalog's own authoritative price. The carrier keeps the catalog's
     * shelf states so the placement's purchasability gate stays whole.
     *
     * @param productId the catalog product id (the line's product identity)
     * @param variantId the catalog variant id, or null for a product-level line
     */
    ResolvedUnitPrice resolveUnitPrice(UUID productId, UUID variantId);

    /**
     * Computes one order's commercial breakdown from its re-priced
     * subtotal: the best applicable promotion, the voucher's discount
     * (validated read-only — redemption happens in
     * {@link #settleVoucher}), and the jurisdiction's tax component.
     *
     * @param consumerId  the buying consumer (voucher per-user limits)
     * @param subtotalMinor the order's re-priced subtotal in minor units
     * @param currency    the order's single ISO-4217 currency
     * @param voucherCode the buyer-supplied voucher code, or null
     */
    CommercialBreakdown computeBreakdown(UUID consumerId, long subtotalMinor,
                                         String currency, String voucherCode);

    /**
     * Records the voucher's redemption against the order — the WRITE half
     * of the voucher contract, called INSIDE the placement transaction so
     * a refused redemption rolls the whole placement back. Idempotent per
     * order (the {@code uq_voucher_redemptions_voucher_order} unique
     * index): a replay for the same (voucher, order) is a logged no-op, and
     * the usage limits re-validate under the voucher row's pessimistic
     * lock (the {@code findByIdForUpdate} house pattern — limits are never
     * read-modify-written counters).
     *
     * @param orderId        the placed order the voucher redeemed against
     * @param consumerId     the buying consumer
     * @param voucherCode    the voucher code the placement carried
     * @param discountMinor  the discount the breakdown froze for this voucher
     * @param currency       the order's currency
     */
    void settleVoucher(UUID orderId, UUID consumerId, String voucherCode,
                       long discountMinor, String currency);

    /**
     * The effective unit price carrier — the placement's re-pricing read.
     * {@code priceListId} is non-null exactly when a price-list override
     * applied (the frozen agreement's provenance).
     */
    record ResolvedUnitPrice(long priceMinor, String currency, UUID providerId,
                             boolean purchasable, String refusal,
                             UUID priceListId) {
    }

    /**
     * One order's commercial breakdown — the facts the order freezes.
     * {@code totalMinor} is the buyer's agreement:
     * {@code subtotal - promotion - voucher + (tax when EXCLUSIVE)} — an
     * INCLUSIVE tax is a component already contained in the agreed prices,
     * so the total stays the discounted subtotal (the displayed-price
     * honesty of inclusive markets).
     */
    record CommercialBreakdown(long subtotalMinor,
                               long promotionDiscountMinor, UUID promotionId, String promotionName,
                               long voucherDiscountMinor, UUID voucherId, String voucherCode,
                               long taxMinor, String taxCountry, boolean taxInclusive,
                               int taxRateBps,
                               long totalMinor) {
    }
}
