package com.marketplace.shared.api;

/**
 * The transaction purpose of a real-estate listing (realestate systems plan
 * L31). Lives in shared-api because the cross-module {@code SearchCriteria}
 * record (L32) carries it as a first-class criterion — the same placement
 * rule as {@code Currencies} (shared type consumed by a domain entity and
 * by the cross-module contract alike).
 */
public enum PropertyPurpose {
    RENT,
    SALE
}
