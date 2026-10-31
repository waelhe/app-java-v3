package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code categoryTranslate}, {@code attributeTranslate} and
 * {@code attributeValueTranslate} mutation roots, the agent-2
 * follow-on packet): the shared translation command the GraphQL
 * boundary hands the catalog owner's translate use case — one
 * language dimension's display-name amendment over a bilingual row.
 * The platform's own two-language law (the bilingual columns'
 * AR/EN closure — the central schema's own two-member
 * {@code LanguageCodeEnum}) is the honest bounded mapping of Saleor's
 * 779-member locale enum: {@code AR} addresses the {@code name_ar}
 * dimension, {@code EN} the {@code name_en} dimension.
 *
 * @param name         the language's display name (null preserves the
 *                     column — the Saleor input's omit-to-preserve
 *                     semantics)
 * @param languageCode the addressed language dimension (AR or EN —
 *                     never null; the SDL's own non-null contract)
 */
public record TranslationAmendCommand(String name, String languageCode) {
}
