package com.marketplace.catalog.spi;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code categoryTranslate}, {@code attributeTranslate},
 * {@code attributeValueTranslate} and {@code collectionTranslate}
 * mutation roots, the agent-2 follow-on packet): the catalog owner's
 * write seam for the bilingual translation family. These are Saleor
 * MANAGE_TRANSLATIONS roots
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

    /**
     * Amends one collection row's language dimension — the Saleor
     * {@code collectionTranslate} root's owning use case (the agent-2
     * L-slice — the storefront collection registry's own bilingual
     * columns).
     *
     * @param id      the row's native UUID (the typed global ID is
     *                decoded at the transport boundary)
     * @param command the translation command (name, languageCode)
     * @return the result with the amended row's projection, or the
     *         domain errors (NOT_FOUND, INVALID blank/oversized name,
     *         INVALID nameless merge)
     */
    CollectionMutationResult translateCollection(UUID id, TranslationAmendCommand command);

    /**
     * Amends a batch of attribute rows' language dimensions — the Saleor
     * {@code attributeBulkTranslate} root's owning use case (the agent-2
     * M-slice — the pinned {@code id + languageCode + translationFields}
     * items over the single-item translate ladder, one transaction with
     * the pinned error policy's own semantics: REJECT_EVERYTHING (the
     * default) rolls the whole batch back through Spring's own official
     * rollback-marking mechanism when any item is refused).
     *
     * @param items       the translation items (row id + the command)
     * @param errorPolicy the pinned policy member name (null = the
     *                    REJECT_EVERYTHING default)
     * @return the per-item outcomes (the amended name in the addressed
     *         dimension, or the item's domain errors)
     */
    List<TranslationBulkOutcome> bulkTranslateAttributes(List<TranslationBulkItem> items,
                                                         String errorPolicy);

    /**
     * Amends a batch of attribute-value rows' language dimensions — the
     * Saleor {@code attributeValueBulkTranslate} root's owning use case
     * (the same one-transaction/policy semantics over the value
     * registry's single-item translate ladder).
     *
     * @param items       the translation items (row id + the command)
     * @param errorPolicy the pinned policy member name (null = the
     *                    REJECT_EVERYTHING default)
     * @return the per-item outcomes (the amended name in the addressed
     *         dimension, or the item's domain errors)
     */
    List<TranslationBulkOutcome> bulkTranslateAttributeValues(List<TranslationBulkItem> items,
                                                              String errorPolicy);
}
