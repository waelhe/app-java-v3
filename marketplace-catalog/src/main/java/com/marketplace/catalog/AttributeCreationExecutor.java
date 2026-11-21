package com.marketplace.catalog;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attribute-definition insert's own transaction door (the 2026-11-07
 * #808 round-19 review; the {@code CategoryCreationExecutor} precedent
 * verbatim): the create's reserved-code precheck is honest but not
 * atomic — a rival create can commit the same slug between the precheck
 * and this insert, and the live-code partial unique index
 * ({@code uq_catalog_attribute_definitions_code_live}) answers the
 * loser with a {@code DataIntegrityViolationException}. A catch INSIDE
 * the flushing transaction returns into a commit that throws
 * {@code UnexpectedRollbackException} instead of the classified
 * ALREADY_EXISTS result (the exact 500 the round-3 category finding
 * proved) — crossing THIS executor's REQUIRES_NEW boundary the loser
 * arrives fully rolled back, and the caller's catch answers the
 * classified payload error.
 */
@Component
public class AttributeCreationExecutor {

    private final CatalogAttributeDefinitionRepository definitions;

    AttributeCreationExecutor(CatalogAttributeDefinitionRepository definitions) {
        this.definitions = definitions;
    }

    /**
     * The insert, in its own transaction: REQUIRES_NEW (the caller's own
     * transaction — if one is open, the bulk boundary — never shares the
     * insert's rollback verdict; the single create runs with none).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    CatalogAttributeDefinition insert(String code, String name, CatalogAttributeType valueType) {
        return definitions.saveAndFlush(
                CatalogAttributeDefinition.create(code, name, null, valueType, null));
    }
}
