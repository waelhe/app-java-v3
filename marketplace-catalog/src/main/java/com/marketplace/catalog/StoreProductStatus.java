package com.marketplace.catalog;

/**
 * The store product's publication/availability lifecycle. DRAFT is only a
 * product state: typed goods are being configured and are not purchasable.
 * Variants keep their narrower ProductStatus vocabulary.
 */
public enum StoreProductStatus {
    DRAFT,
    ACTIVE,
    SUSPENDED,
    ARCHIVED
}
