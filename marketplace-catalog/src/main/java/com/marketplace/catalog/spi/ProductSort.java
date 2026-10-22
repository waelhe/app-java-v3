package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code products}
 * keyset scroll, packet campaign-b4-c): the executed product sort — the
 * owning field with the requested direction (the {@code CategorySort}
 * precedent). The Saleor {@code ProductOrderField} vocabulary closes
 * over fourteen values; only NAME has an owning keyset fact on this
 * platform (the product's own title column) — the other thirteen
 * (RANK/PRICE/MINIMAL_PRICE — search- and channel-dependent facts,
 * LAST_MODIFIED/DATE/LAST_MODIFIED_AT — the audit-instant scrolls a
 * later slice may add, TYPE, PUBLISHED/PUBLICATION_DATE/PUBLISHED_AT —
 * channel-scoped publication facts, COLLECTION, RATING, CREATED_AT)
 * answer the boundary's explicit classified error, so the owner's
 * executed vocabulary closes over NAME alone. The field carries its
 * keyset property name so the scroll, the cursor gate and the emitted
 * cursors share ONE vocabulary.
 *
 * @param field     the owning sort field (never null)
 * @param ascending the requested direction
 */
public record ProductSort(Field field, boolean ascending) {

    /**
     * The owning sort fields — the executed keyset vocabulary. Each value
     * carries the property name the keyset positions (and therefore the
     * opaque cursors) are keyed by, next to the {@code id} tiebreaker.
     */
    public enum Field {

        /**
         * The product's display title — the keyset property name the
         * EXECUTED repository scroll orders by (the title column the
         * derived keyset methods emit), the same vocabulary the
         * StoreProduct connection's TITLE sort carries. The pre-merge
         * rounds declared "name" here while the scroll emitted "title"
         * keys, so the cursor gate rejected page 2 of the NAME sort with
         * the same-sortBy error — the property is the executed sort's
         * own, never a wire label.
         */
        NAME("title");

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
     * The factory — the same shape {@code CategorySort.of} established.
     *
     * @param field     the owning sort field (never null)
     * @param ascending the requested direction
     * @return the sort
     */
    public static ProductSort of(Field field, boolean ascending) {
        if (field == null) {
            throw new IllegalArgumentException("A product sort field is required");
        }
        return new ProductSort(field, ascending);
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
