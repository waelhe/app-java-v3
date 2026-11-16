package com.marketplace.identity.spi;

/**
 * P1 (the canonical Saleor plan §5-P1): the staff-list scroll's sort —
 * the Saleor {@code UserSortingInput} subset the identity owner executes
 * as a real keyset sort (an owning indexed column with the {@code id}
 * tiebreaker). The other Saleor {@code UserSortField} vocabulary values
 * answer an explicit classified error at the GraphQL boundary — a
 * recorded, tested boundary, not a silent fallback.
 */
public record UserAccountSort(Field field, boolean ascending) {

    /** The sortable fields with an owning column on the {@code users} row. */
    public enum Field {
        /** Sort by the account email (case-sensitive, deterministic with the id tiebreaker). */
        EMAIL,
        /** Sort by the account creation instant. */
        CREATED_AT,
        /** Sort by the row's last modification instant. */
        LAST_MODIFIED_AT
    }

    /**
     * Convenience factory mirroring the GraphQL input's shape.
     *
     * @param field     the sort field (never {@code null})
     * @param ascending {@code true} for ASC, {@code false} for DESC
     * @return the sort
     */
    public static UserAccountSort of(Field field, boolean ascending) {
        return new UserAccountSort(field, ascending);
    }
}
