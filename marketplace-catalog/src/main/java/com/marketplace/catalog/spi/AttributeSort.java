package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code attributes}
 * keyset scroll): the executed attribute sort — the owning field with the
 * requested direction (the {@code UserAccountSort}/
 * {@code PermissionGroupSort}/{@code ProductTypeSort} precedent). The
 * Saleor {@code AttributeSortField} vocabulary closes over NAME, SLUG,
 * VALUE_REQUIRED, IS_VARIANT_ONLY, VISIBLE_IN_STOREFRONT and four
 * deprecated no-effect flags; only NAME and SLUG have owning facts on this
 * platform (the rendered display name and the row's immutable code) — the
 * other seven answer the boundary's explicit classified error, so the
 * owner's executed vocabulary closes over NAME and SLUG alone. Each field
 * carries its keyset property name so the scroll, the cursor gate and the
 * emitted cursors share ONE vocabulary.
 *
 * @param field     the owning sort field (never null)
 * @param ascending the requested direction
 */
public record AttributeSort(Field field, boolean ascending) {

    /**
     * The owning sort fields — the executed keyset vocabulary. Each value
     * carries the property name the keyset positions (and therefore the
     * opaque cursors) are keyed by, next to the {@code id} tiebreaker.
     */
    public enum Field {

        /** The rendered primary display name (nameEn, else nameAr). */
        NAME("name"),

        /** The row's immutable public code (the Saleor slug). */
        SLUG("slug");

        private final String property;

        Field(String property) {
            this.property = property;
        }

        /** The keyset property name the cursors of this sort field carry. */
        public String property() {
            return property;
        }
    }

    /**
     * The factory — the same shape {@code UserAccountSort.of} established.
     *
     * @param field     the owning sort field (never null)
     * @param ascending the requested direction
     * @return the sort
     */
    public static AttributeSort of(Field field, boolean ascending) {
        if (field == null) {
            throw new IllegalArgumentException("An attribute sort field is required");
        }
        return new AttributeSort(field, ascending);
    }

    /**
     * The keyset property name of the executed sort — the property the
     * scroll's keyset positions (and the boundary's cursor gate) key by,
     * next to the {@code id} tiebreaker.
     *
     * @return the sort property name ({@code name} or {@code slug})
     */
    public String property() {
        return field.property();
    }
}
