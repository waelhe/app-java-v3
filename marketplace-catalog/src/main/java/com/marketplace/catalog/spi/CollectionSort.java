package com.marketplace.catalog.spi;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor {@code collections}
 * keyset scroll, packet agent-2): the executed collection sort — the
 * owning field with the requested direction (the {@code CategorySort}
 * precedent). The Saleor {@code CollectionSortField} vocabulary closes
 * over NAME and PRODUCT_COUNT; only NAME has an owning fact on this
 * platform (the rendered display name — the registry's bilingual name
 * columns) — PRODUCT_COUNT (a products connection count the scroll does
 * not own) answers the boundary's explicit classified error, so the
 * owner's executed vocabulary closes over NAME alone.
 *
 * @param field     the owning sort field (never null)
 * @param ascending the requested direction
 */
public record CollectionSort(Field field, boolean ascending) {

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
     * The factory — the {@code CategorySort.of} shape.
     *
     * @param field     the owning sort field (never null)
     * @param ascending the requested direction
     * @return the sort
     */
    public static CollectionSort of(Field field, boolean ascending) {
        if (field == null) {
            throw new IllegalArgumentException("A collection sort field is required");
        }
        return new CollectionSort(field, ascending);
    }

    /**
     * The keyset property name of the executed sort.
     *
     * @return the sort property name ({@code name})
     */
    public String property() {
        return field.property();
    }
}
