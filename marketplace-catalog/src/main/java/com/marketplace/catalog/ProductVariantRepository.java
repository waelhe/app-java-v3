package com.marketplace.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * ADR-0012 (the merchandise model's variant depth): the purchasable
 * unit's repository. The soft-delete filter rides {@code BaseEntity}'s
 * {@code @SoftDelete} — a deleted variant is not a roster member (the
 * {@code ProductRepository} stance verbatim).
 *
 * <p><b>The reservation arithmetic is HERE now</b> — the cart-integration
 * slice's own delivery (the ADR's decision 4: the orders-side consumption
 * is this PR, with its own journey proof). The three conditional
 * modifying UPDATEs are the {@code ProductRepository} twins verbatim: one
 * atomic write per call (the Spring Data JPA-official mechanism; the
 * PostgreSQL row lock is the concurrency), the {@code WHERE} carries the
 * whole invariant, and a zero-updated-rows answer IS the refusal — the
 * adapter turns it into the house 409. No read-modify-write exists
 * anywhere on these paths.
 *
 * <p><b>The parent-product state gate</b> rides the reserve's OWN
 * predicate (the CodeRabbit-measured correction, adopted): the product
 * twin re-checks its own row's status because the decremented row IS the
 * gated row; the variant's gate spans TWO rows (the variant's shelf and
 * the parent product's shelf), so the reserve's {@code WHERE} carries an
 * EXISTS over the parent — a suspension committing between the
 * re-pricing read and the reservation write refuses the reservation
 * exactly the way the product path's own-row check refuses it. The
 * cross-row predicate is NATIVE SQL (the {@code insertOnce} precedent in
 * NotificationRepository): JPQL bulk-update subqueries are
 * provider-dependent territory the tree has no precedent for, and the
 * soft-delete filters — the variant's own row AND the parent's — must
 * be stated explicitly here (Hibernate applies {@code @SoftDelete} to
 * its own generated SQL, not to hand-written native statements). Release and commit stay JPQL twins — they
 * guard only the variant row's own counters, the same single-row
 * discipline their product twins follow.
 */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    /**
     * The SKU namespace probe — the seller's own space only (the V219
     * {@code uq_product_variants_provider_sku} index's read side; the
     * index itself is the race backstop, this read is the friendly
     * pre-check that answers the house 409 with the collision named).
     */
    Optional<ProductVariant> findByProviderIdAndSku(UUID providerId, String sku);

    /** The product's live roster (the owner's list surface — the V219 partial index's read). */
    List<ProductVariant> findByProductIdOrderBySkuAsc(UUID productId);

    /**
     * Reserves {@code qty} units of an ACTIVE variant whose free stock
     * covers it — the placement's write (the cart-integration slice).
     *
     * @return 1 when the reservation landed, 0 when the variant is not
     *         ACTIVE or lacks the free stock (the caller's whole-map
     *         reservation rolls back with the refusal)
     */
    @Modifying
    @Query(value = """
            UPDATE product_variants v
               SET reserved_quantity = v.reserved_quantity + :qty
             WHERE v.id = :id
               AND v.variant_status = 'ACTIVE'
               AND v.is_deleted = FALSE
               AND (v.stock_quantity - v.reserved_quantity) >= :qty
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.product_status = 'ACTIVE'
                              AND p.is_deleted = FALSE)
            """, nativeQuery = true)
    int reserve(@Param("id") UUID id, @Param("qty") int qty);

    /**
     * Releases {@code qty} reserved units — the cancellation's write.
     *
     * @return 1 when the release landed, 0 when the variant holds less
     *         than the quantity reserved (never expected on the machine's
     *         guarded path; a zero answer surfaces the inconsistency)
     */
    @Modifying
    @Query("""
            update ProductVariant v
               set v.reservedQuantity = v.reservedQuantity - :qty
             where v.id = :id
               and v.reservedQuantity >= :qty
            """)
    int release(@Param("id") UUID id, @Param("qty") int qty);

    /**
     * Commits {@code qty} reserved units — the fulfillment's write: the
     * reservation becomes a real deduction (both counters fall).
     *
     * @return 1 when the commit landed, 0 when the reserved set is short
     */
    @Modifying
    @Query("""
            update ProductVariant v
               set v.stockQuantity = v.stockQuantity - :qty,
                   v.reservedQuantity = v.reservedQuantity - :qty
             where v.id = :id
               and v.reservedQuantity >= :qty
               and v.stockQuantity >= :qty
            """)
    int commit(@Param("id") UUID id, @Param("qty") int qty);

    /**
     * Restocks {@code qty} units — the return's write (the V225 RMA
     * lifecycle's variant twin): the received goods go back on the
     * variant's shelf, unconditionally (the physical truth).
     */
    @Modifying
    @Query("""
            update ProductVariant v
               set v.stockQuantity = v.stockQuantity + :qty
             where v.id = :id
            """)
    int restock(@Param("id") UUID id, @Param("qty") int qty);
}
