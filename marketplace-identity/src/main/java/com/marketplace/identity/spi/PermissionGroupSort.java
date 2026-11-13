package com.marketplace.identity.spi;

/**
 * P1 (the canonical Saleor plan §5-P1): the permission-group scroll's sort —
 * the Saleor {@code PermissionGroupSortingInput} subset the identity owner
 * executes as a real keyset sort (an owning field with the {@code id}
 * tiebreaker). The pinned Saleor {@code PermissionGroupSortField} vocabulary
 * closes over exactly one value (NAME) and it is owned, so — unlike the
 * staff-list surface — there is no unowned sort-field branch to answer with
 * a classified error; the boundary documents that structural fact instead
 * of faking an unreachable one.
 */
public record PermissionGroupSort(Field field, boolean ascending) {

    /** The sortable fields with an owning value on the group projection. */
    public enum Field {
        /** Sort by the group's display name (deterministic with the id tiebreaker). */
        NAME
    }

    /**
     * Convenience factory mirroring the GraphQL input's shape.
     *
     * @param field     the sort field (never {@code null})
     * @param ascending {@code true} for ASC, {@code false} for DESC
     * @return the sort
     */
    public static PermissionGroupSort of(Field field, boolean ascending) {
        return new PermissionGroupSort(field, ascending);
    }
}
