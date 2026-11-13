package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeBulkUpdate} mutation root, the agent-2 follow-on
 * packet): one update item of the bulk command — the pinned
 * {@code AttributeBulkUpdateInput} shape ({@code id} + the bounded
 * {@code fields} closure) decoded to its native parts.
 *
 * @param id     the attribute row's native UUID (the typed global ID
 *               is decoded at the transport boundary)
 * @param fields the partial update command (name/slug — the
 *               omit-to-preserve semantics)
 */
public record AttributeBulkUpdateItem(UUID id, AttributeUpdateCommand fields) {
}
