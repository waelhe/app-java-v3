package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code categoryCreate}
 * mutation root): the create command over the bounded OWNED field set of
 * the Saleor {@code CategoryInput} (the registry's {@code name_en} and
 * {@code code} columns — the unowned Saleor members stay undeclared on
 * the wire, the recorded divergences).
 */
public record CategoryCreateCommand(String name, String slug) {
}
