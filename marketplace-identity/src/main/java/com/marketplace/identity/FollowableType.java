package com.marketplace.identity;

/**
 * JT-20 (the discovery waves — AC-20-05): the closed vocabulary of the
 * generalized follow's source types — the {@code follows} table's
 * {@code followable_type} values (V177's CHECK is the SQL-side guard,
 * the D-N7 two-sided discipline: this enum is the single source of
 * truth).
 *
 * <p>PROVIDER is deliberately ABSENT — the provider follow keeps its own
 * home ({@code provider_follows}, V93, and its standing /me/follows
 * surface, untouched). The one-home rule (the
 * {@code com.marketplace.shared.api.FollowedSourcesPort} contract) means
 * this enum never grows a PROVIDER value: the identity adapter unions
 * the two homes at read time instead (the port's own javadoc).
 */
public enum FollowableType {

    /**
     * A member follows another member (the users.id space — checked live
     * through the identity module's own UserRepository at write time).
     */
    USER,

    /**
     * A member follows a neighborhood group (the neighborhood_groups.id
     * space — checked live through the shared GroupLookupPort seam).
     */
    GROUP
}
