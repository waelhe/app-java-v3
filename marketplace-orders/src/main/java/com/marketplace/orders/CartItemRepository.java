package com.marketplace.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    /**
     * <b>The cart's lines in their TOTAL ORDER</b> — {@code (createdAt, id)}
     * ascending, the R4 deterministic-order pattern (the intent search's
     * own fix). The placement's seller grouping and the primary-order
     * contract ride the line order, and a derived {@code findByCartId}
     * with NO ordering is an unspecified physical order — heap scan,
     * index scan, whichever the planner picks — so the "first group"
     * could flip between runs (CI-measured on main after V222's union-key
     * swap changed the planner's scan choice: the mixed-seller journey's
     * primary answered the SECOND seller's total). Every order-sensitive
     * consumer reads through this form; the unordered form stays for
     * order-insensitive bulk uses only.
     */
    List<CartItem> findByCartIdOrderByCreatedAtAscIdAsc(UUID cartId);

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
