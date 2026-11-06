package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeValueCreate} mutation root, the agent-2 packet): the
 * create command the GraphQL boundary hands the catalog owner's
 * attribute-value write use case. The bounded field set is the row's
 * own Saleor-owned closure ({@code name} — the bilingual row's name_en)
 * PLUS the platform-extension {@code slug} carrier the row's own NOT
 * NULL code policy requires and the Saleor closure cannot declare (the
 * same "platform-only fields as extensions" policy the VoucherInput's
 * discount facts established): the option's stable public code, a
 * lowercase latin slug unique within the owning attribute. Unowned
 * Saleor {@code AttributeValueCreateInput} members ({@code value} — no
 * swatch carrier, the deprecated {@code richText}/{@code plainText},
 * {@code fileUrl}/{@code contentType} — no file owner,
 * {@code externalReference} — no column) never reach this seam. The
 * append position is the owner's own derivation (max+1 within the
 * attribute) — never a client-supplied fact.
 *
 * @param name the rendered primary display name (the name_en column)
 * @param slug the stable public code (the row's own NOT NULL policy)
 */
public record AttributeValueCreateCommand(String name, String slug) {
}
