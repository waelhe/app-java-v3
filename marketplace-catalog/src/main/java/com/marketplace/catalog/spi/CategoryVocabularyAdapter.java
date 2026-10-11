package com.marketplace.catalog.spi;

import com.marketplace.catalog.CategoryRepository;
import com.marketplace.shared.api.CategoryVocabularyPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stage 9 (ADR-0005): the catalog module's implementation of the
 * {@link CategoryVocabularyPort} cross-module contract — the planning
 * record's category-reference validation reads the dictionary's own keys
 * here (the {@code ProductPricingAdapter} pattern verbatim).
 */
@Component
public class CategoryVocabularyAdapter implements CategoryVocabularyPort {

    private final CategoryRepository categoryRepository;

    public CategoryVocabularyAdapter(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> knownCodes() {
        return categoryRepository.findAll().stream()
                .map(category -> category.getCode())
                .toList();
    }
}
