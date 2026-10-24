package com.marketplace.catalog.spi;

import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeBulkTranslate} and
 * {@code attributeValueBulkTranslate} mutation roots, the agent-2
 * M-slice): one per-item outcome of the translation family's bulk use
 * cases — the single-item ladder's own (addressed-row, language,
 * amended-name, errors) answer at its bulk position. The transport
 * boundary renders it as Saleor's per-item {@code results[i]} entry:
 * the bounded per-locale translation projection (the amended name in
 * the addressed language dimension — the platform's own two-language
 * row law's honest rendering of Saleor's per-locale translation rows)
 * with the item's own error entries.
 *
 * @param rowId        the addressed row's native UUID (the translation
 *                     projection's own backing row)
 * @param languageCode the addressed language dimension (AR or EN)
 * @param name         the amended name in the addressed dimension (the
 *                     merged pair's own value — null when the item was
 *                     refused)
 * @param errors       the item's domain error entries (empty when the
 *                     item succeeded)
 */
public record TranslationBulkOutcome(UUID rowId, String languageCode, String name,
                                     List<AttributeMutationError> errors) {

    /** The item's success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }
}
