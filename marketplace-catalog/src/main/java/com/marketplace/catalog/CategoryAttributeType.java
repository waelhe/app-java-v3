package com.marketplace.catalog;

/**
 * W2 (yelp-level plan §5 — the business page): the closed value-type
 * vocabulary of the category-attribute registry (V89's CHECK) — the
 * declared shape an attribute's VALUE carries.
 *
 * <p>Three types, deliberately closed: TEXT (a short free-text value),
 * NUMBER (a numeric value), BOOLEAN (a yes/no amenity — wifi, parking,
 * electronic payment, the plan's own examples). A fourth type (say, a
 * range or an enum) is a migration-widened decision through the same
 * V68/V69 discipline every house vocabulary follows — never a silent
 * insertion.
 */
public enum CategoryAttributeType {
    TEXT,
    NUMBER,
    BOOLEAN
}
