package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code categoryUpdate}
 * mutation root): the amendment command. A {@code null} field preserves
 * the column — the Saleor input's own omit-to-preserve semantics (never
 * a blank-overwrite and never a REQUIRED violation on update).
 */
public record CategoryUpdateCommand(String name, String slug) {
}
