package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeValueUpdate} mutation root, the agent-2 packet): the
 * partial-update command the GraphQL boundary hands the catalog
 * owner's attribute-value write use case. {@code null} fields preserve
 * the row's column (the Saleor input's omit-to-preserve semantics); an
 * explicit blank name answers the INVALID domain error (the entity's
 * own at-least-one-name contract, re-checked over the MERGED pair).
 * The row's stable public code is immutable (the entity's own identity
 * law): a slug equal to the row's own code is the idempotent re-send,
 * any different slug answers the classified INVALID domain error.
 *
 * @param name the rendered primary display name (the name_en column)
 * @param slug the stable public code (the row's own slug mapping)
 */
public record AttributeValueUpdateCommand(String name, String slug) {

    /** The omit-everything no-op — answered by the current projection. */
    public boolean isEmpty() {
        return name == null && slug == null;
    }
}
