package com.marketplace.pricing.spi;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor {@code vouchers}
 * keyset scroll): the executed voucher sort — the owning field with
 * the requested direction (the {@code PromotionSort}/
 * {@code PermissionGroupSort} precedent).
 *
 * <p><b>The executed vocabulary closes over the owning NON-NULL keyset
 * columns only</b> — the same honest boundary {@code PromotionSort}
 * documents: a NULL property value cannot travel in the framework's
 * map-based keyset cursor, so an optional column can never carry a
 * deterministic keyset cursor on the house contract. The Saleor
 * {@code VoucherSortField} vocabulary closes over CODE (deprecated
 * upstream), NAME, START_DATE, END_DATE, VALUE, TYPE, USAGE_LIMIT and
 * MINIMUM_SPENT_AMOUNT:</p>
 * <ul>
 *   <li>CODE — the row's non-null normalized {@code code} column
 *       (deprecated upstream but still the only owned deterministic
 *       keyset field on this platform): executes.</li>
 *   <li>NAME — optional on this platform (nullable column): the
 *       boundary answers the explicit classified not-implemented
 *       error.</li>
 *   <li>START_DATE / END_DATE — the policy window is OPTIONAL here:
 *       same explicit not-implemented error.</li>
 *   <li>USAGE_LIMIT — the redemption limit is optional here
 *       (null = unlimited): same explicit not-implemented error.</li>
 *   <li>VALUE / MINIMUM_SPENT_AMOUNT — channel-scoped money upstream,
 *       and the frozen money contract forbids authoritative amounts on
 *       this wire: explicit not-implemented error.</li>
 *   <li>TYPE — no voucher-type discrimination exists on this platform
 *       (every voucher is an order-level subtotal discount): explicit
 *       not-implemented error.</li>
 * </ul>
 *
 * @param field     the owning sort field (never null)
 * @param ascending the requested direction
 */
public record VoucherSort(Field field, boolean ascending) {

    /** The owning sort fields — the executed keyset vocabulary. */
    public enum Field {

        /** The row's non-null normalized code. */
        CODE
    }

    /**
     * The factory — the same shape {@code UserAccountSort.of} established.
     *
     * @param field     the owning sort field (never null)
     * @param ascending the requested direction
     * @return the sort
     */
    public static VoucherSort of(Field field, boolean ascending) {
        if (field == null) {
            throw new IllegalArgumentException("A voucher sort field is required");
        }
        return new VoucherSort(field, ascending);
    }
}
