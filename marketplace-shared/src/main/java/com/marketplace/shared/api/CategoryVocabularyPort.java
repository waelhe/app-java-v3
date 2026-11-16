package com.marketplace.shared.api;

import java.util.List;

/**
 * Stage 9 (plan D-11, ADR-0005): the category vocabulary seam — the
 * planning record's reference validation (a category ref the catalog
 * does not know is refused BEFORE any write). The {@code console} module
 * never imports the catalog module (the house port discipline); the
 * catalog owns the implementation (the {@code ProductPricingAdapter}
 * pattern verbatim).
 */
public interface CategoryVocabularyPort {

    /**
     * The catalog's known category codes (the stable API-facing keys).
     *
     * @return the codes (never null; possibly empty)
     */
    List<String> knownCodes();
}
