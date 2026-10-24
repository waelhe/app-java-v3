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
}
