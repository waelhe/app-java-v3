package com.marketplace.shared.api;

/**
 * The physical kind of a real-estate listing (realestate systems plan L31 —
 * the plan's enumerated set: APARTMENT/VILLA/LAND/SHOP/OFFICE/GARAGE).
 * Lives in shared-api for the same reason as {@link PropertyPurpose}: the
 * cross-module {@code SearchCriteria} record (L32) carries it as a
 * criterion, and the realestate entity reuses it (the {@code Currencies}
 * precedent).
 */
public enum PropertyType {
    APARTMENT,
    VILLA,
    LAND,
    SHOP,
    OFFICE,
    GARAGE
}
