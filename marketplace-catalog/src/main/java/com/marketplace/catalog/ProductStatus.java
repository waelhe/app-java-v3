package com.marketplace.catalog;

/**
 * The purchasable unit's availability lifecycle (V219): a variant can be
 * ACTIVE, SUSPENDED, or ARCHIVED. Draft publication is product-only and is
 * modeled separately by StoreProductStatus.
 */
public enum ProductStatus {
    ACTIVE,
    SUSPENDED,
    ARCHIVED
}
