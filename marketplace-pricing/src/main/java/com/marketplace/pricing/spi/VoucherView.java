package com.marketplace.pricing.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor {@code voucher} and
 * {@code vouchers} read roots): the pricing owner's bounded voucher
 * projection — one element of the platform's code-keyed discount-policy
 * collection rendered behind the Saleor {@code Voucher} wire type.
 *
 * <p><b>The honest mapping (the bounded-projection method the User/Group/
 * ProductType read slices established):</b> only facts the {@code vouchers}
 * row (and its redemption accounting) actually owns travel here:</p>
 * <ul>
 *   <li>{@code id} — the native {@code vouchers} primary key. The GraphQL
 *       boundary encodes it as a typed {@code Voucher} global ID — never
 *       a raw UUID on the wire.</li>
 *   <li>{@code code} — the row's NORMALIZED code (trimmed, upper-cased —
 *       the entity's own contract); never null.</li>
 *   <li>{@code name} — the row's optional display name (null is a real
 *       authored state).</li>
 *   <li>{@code currency} — the policy row's own ISO-4217 denomination
 *       (never null): the voucher's FIXED amount and minimum spend are
 *       authored FOR this currency and never re-denominated.</li>
 *   <li>{@code discountValueType} — the owner's own PERCENTAGE/FIXED
 *       vocabulary (Saleor's {@code DiscountValueTypeEnum} maps 1:1).</li>
 *   <li>{@code usageLimit} — the row's global redemption limit
 *       ({@code maxRedemptions}; null = unlimited) — Saleor's
 *       {@code usageLimit} semantics exactly.</li>
 *   <li>{@code timesUsed} — the honest usage count: redemption rows
 *       COUNTED ({@code VoucherRedemptionRepository.countByVoucherId}),
 *       never a read-modify-written counter — the owner's own accounting
 *       law.</li>
 *   <li>{@code applyOncePerCustomer} — the truthful flattening of the
 *       row's per-user limit: {@code true} exactly when
 *       {@code maxRedemptionsPerUser == 1} (a limit of N&gt;1 or unlimited
 *       renders {@code false} — the richer N-per-customer fact stays with
 *       the owner's admin surface; the registered flattening divergence
 *       row).</li>
 *   <li>{@code startsAt}/{@code endsAt} — the policy window, OPTIONAL on
 *       the platform (same nullability divergence as the promotion
 *       window — never a fabricated epoch).</li>
 * </ul>
 *
 * <p><b>Deliberately absent — the value facts:</b> {@code discountValue}
 * (basis points or minor units) and {@code minSpendMinor} are
 * authoritative amounts the frozen money contract forbids rendering as
 * GraphQL {@code Float}/{@code Money}; they stay on the owner's REST
 * admin surface until the exact-decimal rendering decision lands
 * (registry row). Saleor fields with no owning fact — the voucher-type
 * discrimination, single-use codes model, staff-only flag, catalogue
 * scoping, channel listings, metadata, translations — stay undeclared at
 * the boundary, never faked here.</p>
 *
 * @param id                 the native {@code vouchers} primary key (never null)
 * @param code               the row's normalized code (never null)
 * @param name               the row's optional display name
 * @param currency           the row's ISO-4217 currency (never null)
 * @param discountValueType  the owner's PERCENTAGE/FIXED vocabulary
 * @param usageLimit         the global redemption limit (null = unlimited)
 * @param timesUsed          the redemption-row count (never negative)
 * @param applyOncePerCustomer true exactly when the per-user limit is 1
 * @param startsAt           the policy window's start (null = no start bound)
 * @param endsAt             the policy window's end (null = no end bound)
 */
public record VoucherView(
        UUID id,
        String code,
        String name,
        String currency,
        DiscountType discountValueType,
        Long usageLimit,
        long timesUsed,
        boolean applyOncePerCustomer,
        Instant startsAt,
        Instant endsAt
) {

    /**
     * The owner's discount-value vocabulary — the closed PERCENTAGE/FIXED
     * set the entity's own CHECK pins; the boundary renders it as Saleor's
     * {@code DiscountValueTypeEnum} verbatim.
     */
    public enum DiscountType {

        /** Basis points (1..10000 = 0.01%..100%). */
        PERCENTAGE,

        /** Minor units of the row's own currency. */
        FIXED
    }
}
