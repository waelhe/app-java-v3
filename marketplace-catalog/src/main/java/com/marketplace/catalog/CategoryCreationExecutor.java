package com.marketplace.catalog;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The category INSERT's own transaction door (the 2026-11-06 #808 round
 * review, Minor · Data Integrity &amp; Integration — the
 * {@code AdBillingBatchExecutor} precedent verbatim): the loser of two
 * concurrent same-code creates must answer the classified
 * {@code ALREADY_EXISTS} — but the DataIntegrityViolationException marks
 * the transaction that flushed the INSERT rollback-only, and a catch
 * INSIDE that transaction returns into a commit that throws
 * {@code UnexpectedRollbackException} instead (the raw 500 the finding
 * proved). The insert — position derivation included — rides THIS bean's
 * own transaction; the violation crosses its boundary fully rolled back,
 * and the caller's catch classifies a CLEANLY-rolled-back loser.
 */
@Component
class CategoryCreationExecutor {

    private final CategoryRepository categoryRepository;

    CategoryCreationExecutor(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * The insert plus its own append-position derivation, in ONE
     * transaction — the position read and the INSERT serialize with every
     * other writer the same way the in-service version did. REQUIRES_NEW
     * (the {@code AdBillingBatchExecutor.settleOneCampaign} door verbatim):
     * the caller's own transaction — if one is open — never shares the
     * insert's rollback verdict.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    Category insert(String code, String name) {
        return categoryRepository.saveAndFlush(Category.create(code, name, null,
                categoryRepository.findAllByOrderByPositionAscIdAsc().stream()
                        .mapToInt(Category::getPosition)
                        .max().orElse(0) + 1));
    }
}
