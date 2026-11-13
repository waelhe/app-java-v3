package com.marketplace.catalog;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The product-type insert's own transaction door (the 2026-11-07 #808
 * round-19 review; the {@code CategoryCreationExecutor} and
 * {@code AttributeCreationExecutor} precedent verbatim): the create's
 * reserved-code precheck is honest but not atomic — a rival create can
 * commit the same slug between the precheck and this insert, and the
 * live-code partial unique index ({@code uq_product_types_code_live})
 * answers the loser with a {@code DataIntegrityViolationException}. A
 * catch INSIDE the flushing transaction returns into a commit that
 * throws {@code UnexpectedRollbackException} instead of the classified
 * ALREADY_EXISTS result — crossing THIS executor's REQUIRES_NEW boundary
 * the loser arrives fully rolled back, and the caller's catch answers
 * the classified payload error.
 */
@Component
public class ProductTypeCreationExecutor {

    private final ProductTypeRepository productTypes;

    ProductTypeCreationExecutor(ProductTypeRepository productTypes) {
        this.productTypes = productTypes;
    }

    /**
     * The insert, in its own transaction: REQUIRES_NEW (the caller — the
     * non-transactional create surface — never shares the insert's
     * rollback verdict).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ProductType insert(String code, String nameEn, String nameAr, String description) {
        return productTypes.saveAndFlush(ProductType.create(code, nameEn, nameAr, description));
    }
}
