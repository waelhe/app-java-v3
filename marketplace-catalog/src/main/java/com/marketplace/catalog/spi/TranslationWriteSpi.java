package com.marketplace.catalog.spi;

import org.springframework.modulith.NamedInterface;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code categoryTranslate}, {@code attributeTranslate} and
 * {@code attributeValueTranslate} mutation roots, the agent-2
 * follow-on packet): the catalog owner's write seam for the bilingual
 * translation family. These are Saleor MANAGE_TRANSLATIONS roots
 * ("Requires one of the following permissions: MANAGE_TRANSLATIONS")
 * mapped through the platform's ADMIN capability and enforced HERE at
 * the owning use-case boundary by Spring Security's method security —
 * the same {@code @PreAuthorize} mechanism every write family in this
 * module established — never only in a resolver.
 *
 * <p><b>The platform's own two-language law is the honest bounded
 * mapping</b> of Saleor's per-locale translation rows: the bilingual
 * columns ({@code name_en}/{@code name_ar}) ARE the platform's
 * translation storage — {@code AR} addresses the {@code name_ar}
 * dimension, {@code EN} the {@code name_en} dimension — and the
 * registry's own at-least-one-name law governs the merged pair. The
 * result contracts are the owning registries' own payload shapes (the
 * registered "Mutation payload errors" ADAPT divergence): domain
 * validation outcomes answer as the family's own error entries, never
 * as thrown exceptions.</p>
 */
@NamedInterface("catalog-spi")
public interface TranslationWriteSpi {

    /**
     * Amends one category row's language dimension — the Saleor
     * {@code categoryTranslate} root's owning use case.
     *
     * @param id      the row's native UUID (the typed global ID is
     *                decoded at the transport boundary)
     * @param command the translation command (name, languageCode)
     * @return the result with the amended row's projection, or the
     *         domain errors (NOT_FOUND, INVALID blank/oversized name,
     *         INVALID nameless merge)
     */
    CategoryMutationResult translateCategory(UUID id, TranslationAmendCommand command);

    /**
     * Amends one attribute row's language dimension — the Saleor
     * {@code attributeTranslate} root's owning use case.
     *
     * @param id      the row's native UUID
     * @param command the translation command (name, languageCode)
     * @return the result with the amended row's projection, or the
     *         domain errors (NOT_FOUND, INVALID)
     */
    AttributeMutationResult translateAttribute(UUID id, TranslationAmendCommand command);

    /**
     * Amends one attribute-value row's language dimension — the Saleor
     * {@code attributeValueTranslate} root's owning use case.
     *
     * @param id      the row's native UUID
     * @param command the translation command (name, languageCode)
     * @return the result with the amended value's projection (and the
     *         owning attribute's own projection — Saleor's dual-field
     *         payload), or the domain errors (NOT_FOUND, INVALID)
     */
    AttributeValueMutationResult translateAttributeValue(UUID id, TranslationAmendCommand command);
}
