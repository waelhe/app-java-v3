package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code categories}
 * keyset scroll, packet campaign-b4-a): the executed category sort — the
 * owning field with the requested direction (the {@code UserAccountSort}/
 * {@code PermissionGroupSort}/{@code AttributeSort} precedent). The
 * Saleor {@code CategorySortField} vocabulary closes over NAME,
 * PRODUCT_COUNT and SUBCATEGORY_COUNT; only NAME has an owning fact on
 * this platform (the rendered display name — the registry's bilingual
 * name columns) — PRODUCT_COUNT and SUBCATEGORY_COUNT (products and a
 * category TREE the flat registry does not own) answer the boundary's
 * explicit classified error, so the owner's executed vocabulary closes
 * over NAME alone. The field carries its keyset property name so the
 * scroll, the cursor gate and the emitted cursors share ONE vocabulary.
 *
 * @param field     the owning sort field (never null)
 * @param ascending the requested direction
 */
public record CategorySort(Field field, boolean ascending) {

    /**
     * The owning sort fields — the executed keyset vocabulary. Each value
     * carries the property name the keyset positions (and therefore the
     * opaque cursors) are keyed by, next to the {@code id} tiebreaker.
     */
    public enum Field {

        /** The rendered primary display name (nameEn, else nameAr). */
        NAME("name");

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
    public static CategorySort of(Field field, boolean ascending) {
        if (field == null) {
            throw new IllegalArgumentException("A category sort field is required");
        }
        return new CategorySort(field, ascending);
    }

    /**
     * The keyset property name of the executed sort — the property the
     * scroll's keyset positions (and the boundary's cursor gate) key by,
     * next to the {@code id} tiebreaker.
     *
     * @return the sort property name ({@code name})
     */
    public String property() {
        return field.property();
    }
}
