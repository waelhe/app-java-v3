package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProductVariantAttributeValueRepository extends JpaRepository<ProductVariantAttributeValue, UUID> {
    List<ProductVariantAttributeValue> findAllByProductVariantId(UUID productVariantId);
}
