package com.marketplace.pricing.spi;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor {@code promotions}
 * keyset scroll): the executed promotion sort — the owning field with
 * the requested direction (the {@code UserAccountSort}/
 * {@code PermissionGroupSort}/{@code ProductTypeSort} precedent).
 *
 * <p><b>The executed vocabulary closes over the owning NON-NULL keyset
 * columns only</b> — the honest boundary the house keyset contract
 * pins: a cursor must identify one row's own sort key
 * {@code (property, id)}, and a NULL property value cannot travel in
 * the framework's map-based keyset cursor (the position map rejects
 * null values), so an optional column can never carry a deterministic
 * keyset cursor on this contract. The Saleor
 * {@code PromotionSortField} vocabulary closes over NAME,
 * START_DATE, END_DATE and CREATED_AT:</p>
 * <ul>
 *   <li>NAME — the row's non-null {@code name} column: executes.</li>
 *   <li>CREATED_AT — the non-null audit instant every {@code BaseEntity}
 *       row owns: executes.</li>
 *   <li>START_DATE / END_DATE — the policy window is OPTIONAL on this
 *       platform (nullable columns): the boundary answers the explicit
 *       classified not-implemented error, never a shaky sort.</li>
 * </ul>
 *
 * @param field     the owning sort field (never null)
 * @param ascending the requested direction
 */
public record PromotionSort(Field field, boolean ascending) {

    /** The owning sort fields — the executed keyset vocabulary. */
    public enum Field {

        /** The policy row's non-null name. */
        NAME,

        /** The row's non-null creation instant (BaseEntity). */
        CREATED_AT
    }

    /**
     * The factory — the same shape {@code UserAccountSort.of} established.
     *
     * @param field     the owning sort field (never null)
     * @param ascending the requested direction
     * @return the sort
     */
    public static PromotionSort of(Field field, boolean ascending) {
        if (field == null) {
            throw new IllegalArgumentException("A promotion sort field is required");
        }
        return new PromotionSort(field, ascending);
    }
}
