package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogAttributeOptionRepository extends JpaRepository<CatalogAttributeOption, UUID> {
    List<CatalogAttributeOption> findAllByAttributeDefinitionIdOrderByPositionAsc(UUID attributeDefinitionId);
    Optional<CatalogAttributeOption> findByAttributeDefinitionIdAndCode(UUID attributeDefinitionId, String code);
    boolean existsByAttributeDefinitionIdAndPosition(UUID attributeDefinitionId, int position);
    long countByAttributeDefinitionId(UUID attributeDefinitionId);

    /**
     * The append-position derivation's own lookup — the owner's max+1
     * semantics (the agent-2 packet's attributeValueCreate root): the
     * highest-positioned live option of the attribute, empty when the
     * attribute has none yet (position 0 starts the sequence).
     */
    Optional<CatalogAttributeOption> findFirstByAttributeDefinitionIdOrderByPositionDesc(UUID attributeDefinitionId);
}
