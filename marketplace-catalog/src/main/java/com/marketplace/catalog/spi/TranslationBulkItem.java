package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeBulkTranslate} and
 * {@code attributeValueBulkTranslate} mutation roots, the agent-2
 * M-slice): one item of the bulk translation command the GraphQL
 * boundary hands the catalog owner's translation use case — the
 * pinned {@code AttributeBulkTranslateInput}/
 * {@code AttributeValueBulkTranslateInput} shape's bounded owned field
 * set (the addressed row's {@code id} as the native UUID after the
 * typed global-ID decode, the language dimension, the translation
 * fields). The unowned members ({@code externalReference} — no
 * external-ID column) stay undeclared at the SDL boundary.
 *
 * @param rowId   the addressed row's native UUID (an attribute
 *                definition or a CHOICE option, by the owning family)
 * @param command the translation command (name, languageCode)
 */
public record TranslationBulkItem(UUID rowId, TranslationAmendCommand command) {
}
