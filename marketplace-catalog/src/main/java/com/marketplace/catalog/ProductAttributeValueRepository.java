package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProductAttributeValueRepository extends JpaRepository<ProductAttributeValue, UUID> {
    List<ProductAttributeValue> findAllByProductId(UUID productId);
    List<ProductAttributeValue> findAllByProductIdOrderByAssignmentIdAsc(UUID productId);
}
