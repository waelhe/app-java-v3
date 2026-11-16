package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogAttributeDefinitionRepository extends JpaRepository<CatalogAttributeDefinition, UUID> {
    Optional<CatalogAttributeDefinition> findByCode(String code);
    List<CatalogAttributeDefinition> findAllByOrderByCodeAsc();
}
