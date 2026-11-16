package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * S6 (comprehensive repair plan §10/2.3): the registry's persistence port.
 * {@code @SoftDelete} (BaseEntity) already filters {@code is_deleted = false}
 * from every derived query — the partial unique index and these lookups stay
 * aligned (no TOCTOU window, the V47 slug precedent).
 */
public interface CategoryRepository extends JpaRepository<Category, java.util.UUID> {

    /** The write gate's precheck — an ACTIVE registry row for the code or empty. */
    Optional<Category> findByCode(String code);

    /** The public read, in the registry's display order. */
    List<Category> findAllByOrderByPositionAsc();
}
