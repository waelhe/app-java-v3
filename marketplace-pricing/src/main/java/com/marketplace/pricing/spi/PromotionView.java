package com.marketplace.pricing.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor {@code promotion} and
 * {@code promotions} read roots): the pricing owner's bounded promotion
 * projection — one element of the platform's order-level discount-policy
 * collection rendered behind the Saleor {@code Promotion} wire type.
 *
 * <p><b>The honest mapping (the bounded-projection method the User/Group/
 * ProductType read slices established):</b> only facts the
 * {@code promotions} row actually owns travel here. The row IS one
 * order-level discount policy — an explicit, window-bounded,
 * currency-scoped rule the commercial engine resolves as the BEST single
 * applicable promotion per order ({@code CommercialPricingService}).
 * The projection therefore carries:</p>
 * <ul>
 *   <li>{@code id} — the native {@code promotions} primary key. The
 *       GraphQL boundary encodes it as a typed {@code Promotion} global
 *       ID — never a raw UUID on the wire.</li>
 *   <li>{@code name} — the policy row's own non-null name.</li>
 *   <li>{@code orderLevel} — the owner's application level: {@code true}
 *       for every platform promotion (the entity's own contract — order
 *       subtotal, never a catalogue predicate). The boundary renders it
 *       as Saleor's {@code PromotionTypeEnum.ORDER}.</li>
 *   <li>{@code startsAt}/{@code endsAt} — the policy window. Both are
 *       OPTIONAL on the platform (an unbounded or open-ended window is a
 *       real authored state), so the wire nullability diverges from
 *       Saleor's non-null {@code startDate} — the registered
 *       nullability divergence row, never a fabricated epoch.</li>
 *   <li>{@code createdAt}/{@code updatedAt} — the audit instants every
 *       {@code BaseEntity} row owns (never null).</li>
 * </ul>
 *
 * <p><b>Deliberately absent — the value facts:</b> the row's
 * {@code discountType}, {@code discountValue} (basis points or minor
 * units), {@code minSpendMinor}, {@code currency} and {@code active}
 * have NO owning field on Saleor's {@code Promotion} type (the upstream
 * model carries them inside {@code rules[].rewardValue/rewardValueType}
 * with channel scoping), and the frozen money contract forbids rendering
 * authoritative amounts as GraphQL {@code Float}. They stay on the
 * owner's REST admin surface and off this wire until a bounded rule
 * closure with an exact-decimal rendering decision lands — recorded as
 * divergence registry rows, never faked here.</p>
 *
 * @param id         the native {@code promotions} primary key (never null)
 * @param name       the policy row's name (never null)
 * @param orderLevel the owner's application level — true for every
 *                   platform promotion (order-level by the entity's own
 *                   contract)
 * @param startsAt   the policy window's start (null = no start bound)
 * @param endsAt     the policy window's end (null = no end bound)
 * @param createdAt  the row's creation instant (never null)
 * @param updatedAt  the row's last-update instant (never null)
 */
public record PromotionView(
        UUID id,
        String name,
        boolean orderLevel,
        Instant startsAt,
        Instant endsAt,
        Instant createdAt,
        Instant updatedAt
) {
}
