package com.marketplace.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A-17 (C.7): the store categories dictionary's repository — the V70
 * {@code CategoryRepository} twin. The soft-delete filter rides
 * {@code BaseEntity}'s {@code @SoftDelete} (a retired row releases its
 * code for reuse, the V47/V70 partial-unique precedent).
 */
public interface StoreCategoryRepository extends JpaRepository<StoreCategory, UUID> {

    /** The write gate's precheck — a live dictionary row for the code or empty. */
    Optional<StoreCategory> findByCode(String code);

    /** The storefront read, in the dictionary's display order. */
    List<StoreCategory> findAllByOrderByPositionAsc();
}
