package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for reusable ProductType reference data. */
public interface ProductTypeRepository extends JpaRepository<ProductType, UUID> {
    Optional<ProductType> findByCode(String code);
    List<ProductType> findAllByOrderByCodeAsc();
}
