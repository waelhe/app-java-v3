package com.marketplace.catalog;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A-17 (C.7): the M1 store root's repository. The {@code ProductLookupAdapter}
 * resolves media targets through {@link JpaRepository#findById} — the
 * soft-delete filter rides {@code BaseEntity}'s {@code @SoftDelete}, so a
 * deleted product is not a media target (the port's 404 contract).
 */
public interface ProductRepository extends JpaRepository<Product, UUID> {
}
