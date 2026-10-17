package com.marketplace.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByCartId(UUID cartId);

    Optional<CartItem> findByCartIdAndProductId(UUID cartId, UUID productId);

    /**
     * ADR-0012 (the cart-integration slice): the variant line's union
     * lookup — the duplicate add of the SAME variant collapses into the
     * quantity bump (the V222 widened union key's read side).
     */
    Optional<CartItem> findByCartIdAndProductIdAndVariantId(UUID cartId, UUID productId, UUID variantId);

    /**
     * The product-level line's union lookup — {@code variant_id IS NULL}
     * is its own query shape (a derived {@code = NULL} predicate never
     * matches in SQL; Spring Data JPA derives the correct IS NULL form
     * from the {@code IsNull} suffix — the documented query-method
     * keyword), so the product-level duplicate add keeps collapsing
     * exactly as it did before variants existed.
     */
    Optional<CartItem> findByCartIdAndProductIdAndVariantIdIsNull(UUID cartId, UUID productId);
}
