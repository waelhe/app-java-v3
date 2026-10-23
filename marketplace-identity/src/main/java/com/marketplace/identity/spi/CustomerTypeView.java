package com.marketplace.identity.spi;

import java.util.List;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * customer-type registry projection — the assigned attribute IDs in
 * position order (catalog-registered UUIDs whose existence the
 * composition boundary validates through the catalog's declared read
 * SPI).
 */
public record CustomerTypeView(UUID id, String name, String slug, boolean defaultType,
                               List<UUID> attributeIds) {
}
