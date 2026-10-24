package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productVariants} keyset scroll, packet campaign-b5-a): the
 * executed product-variant sort — the owning field with the requested
 * direction (the {@code ProductSort}/{@code CategorySort} precedent).
 * The Saleor {@code ProductVariantSortField} vocabulary closes over
 * exactly TWO values in the pinned 3.23.43 schema, and BOTH have an
 * owning keyset fact on this platform: {@code LAST_MODIFIED_AT} (the
 * entity's own non-null {@code updated_at} audit column — the
 * {@code BaseEntity} contract, deterministic with the {@code id}
 * tiebreaker) and {@code ID} (the row's own unique key). The field
 * carries its keyset property name so the scroll, the cursor gate and
 * the emitted cursors share ONE vocabulary.
 *
 * @param field     the owning sort field (never null)
 * @param ascending the requested direction
 */
public record ProductVariantSort(Field field, boolean ascending) {

    /**
     * The owning sort fields — the executed keyset vocabulary (the full
     * pinned enum; every value executes). Each value carries the
     * property name the keyset positions (and therefore the opaque
     * cursors) are keyed by, next to the {@code id} tiebreaker.
     */
    public enum Field {

        /** The variant's non-null last-modification instant (BaseEntity's {@code updated_at}). */
        LAST_MODIFIED_AT("updatedAt"),

        /** The variant's own unique key (the pinned enum's own 3.23 value). */
        ID("id");

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
     * The factory — the same shape {@code ProductSort.of} established.
     *
     * @param field     the owning sort field (never null)
     * @param ascending the requested direction
     * @return the sort
     */
    public static ProductVariantSort of(Field field, boolean ascending) {
        if (field == null) {
            throw new IllegalArgumentException("A product-variant sort field is required");
        }
        return new ProductVariantSort(field, ascending);
    }

    /**
     * The keyset property name of the executed sort — the property the
     * scroll's keyset positions (and the boundary's cursor gate) key by,
     * next to the {@code id} tiebreaker.
     *
     * @return the sort property name ({@code updatedAt} or {@code id})
     */
    public String property() {
        return field.property();
    }
}
