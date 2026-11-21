package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code productTypes}
 * keyset scroll): the executed product-type sort — the owning field with
 * the requested direction (the {@code UserAccountSort}/
 * {@code PermissionGroupSort} precedent). The Saleor
 * {@code ProductTypeSortField} vocabulary closes over NAME, DIGITAL
 * (deprecated upstream) and SHIPPING_REQUIRED; only NAME has an owning
 * fact on this platform (the rendered display name) — the other two answer
 * the boundary's explicit classified error, so the owner's executed
 * vocabulary closes over NAME alone.
 *
 * @param field     the owning sort field (never null)
 * @param ascending the requested direction
 */
public record ProductTypeSort(Field field, boolean ascending) {

    /** The owning sort fields — the executed keyset vocabulary. */
    public enum Field {

        /** The rendered primary display name (nameEn, else nameAr). */
        NAME
    }

    /**
     * The factory — the same shape {@code UserAccountSort.of} established.
     *
     * @param field     the owning sort field (never null)
     * @param ascending the requested direction
     * @return the sort
     */
    public static ProductTypeSort of(Field field, boolean ascending) {
        if (field == null) {
            throw new IllegalArgumentException("A product-type sort field is required");
        }
        return new ProductTypeSort(field, ascending);
    }
}
