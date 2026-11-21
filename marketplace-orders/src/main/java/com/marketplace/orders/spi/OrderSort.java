package com.marketplace.orders.spi;

/**
 * P4 (the canonical Saleor plan §5-P4 — the Saleor {@code orders}
 * keyset scroll, packet campaign-b5-b): the executed order sort — the
 * owning field with the requested direction (the
 * {@code ProductSort}/{@code UserAccountSort} precedent). The Saleor
 * {@code OrderSortField} vocabulary closes over eight values; three
 * have an owning keyset fact on this platform:
 * {@code CREATED_AT} (the order's own non-null {@code placed_at}
 * placement instant — the D-N5 complete ordering key's own column,
 * deterministic with the {@code id} tiebreaker; the deprecated
 * {@code CREATION_DATE} alias rides the SAME executed sort),
 * {@code LAST_MODIFIED_AT} (the entity's own non-null
 * {@code updated_at} audit column), and {@code STATUS} (the order's
 * own non-null lifecycle column — the enum's stored text, id
 * tiebreaker). The other five (NUMBER — no order-number column,
 * RANK — search-scoped upstream, CUSTOMER — an identity-side join
 * this module does not own, PAYMENT and FULFILLMENT_STATUS —
 * deprecated upstream against the legacy payments API) answer the
 * boundary's explicit classified error. The field carries its keyset
 * property name so the scroll, the cursor gate and the emitted
 * cursors share ONE vocabulary.
 *
 * @param field     the owning sort field (never null)
 * @param ascending the requested direction
 */
public record OrderSort(Field field, boolean ascending) {

    /**
     * The owning sort fields — the executed keyset vocabulary. Each value
     * carries the property name the keyset positions (and therefore the
     * opaque cursors) are keyed by, next to the {@code id} tiebreaker.
     */
    public enum Field {

        /** The order's placement instant (the D-N5 ordering key's own column). */
        CREATED_AT("placedAt"),

        /** The entity's own non-null last-modification audit column. */
        LAST_MODIFIED_AT("updatedAt"),

        /** The order's own lifecycle state (the stored enum text). */
        STATUS("status");

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
    public static OrderSort of(Field field, boolean ascending) {
        if (field == null) {
            throw new IllegalArgumentException("An order sort field is required");
        }
        return new OrderSort(field, ascending);
    }

    /**
     * The keyset property name of the executed sort — the property the
     * scroll's keyset positions (and the boundary's cursor gate) key by,
     * next to the {@code id} tiebreaker.
     *
     * @return the sort property name ({@code placedAt}, {@code updatedAt}
     *         or {@code status})
     */
    public String property() {
        return field.property();
    }
}
