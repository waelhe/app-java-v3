package com.marketplace.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
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
 *
 * <p><b>Campaign packet campaign-b5-a (the Saleor product-variant read
 * roots): the read mirror of the write path's own two-row gate.</b> The
 * PUBLIC population's scrolls are NATIVE keyset SQL for the same reason
 * the reserve is: the population gate spans TWO rows (the variant's
 * ACTIVE shelf AND the parent product's ACTIVE shelf — Saleor's own
 * public-variant population is the variants of published products), and
 * a derived method cannot express the parent EXISTS over the plain
 * {@code product_id} column. Each scroll carries its OWN keyset
 * predicate (the expanded row-value comparison over the executed sort
 * keys — deterministic with the {@code id} tiebreaker), states BOTH
 * soft-delete filters explicitly, and fetches one row PAST the page so
 * the service's {@code hasNext} is the database's own answer — REAL SQL
 * forward-only keyset scrolling, never an offset. The ADMIN
 * population's scrolls are the derived {@code Window}-returning methods
 * (the {@code scrollStaffUsers} precedent): no gate at all — the
 * include-unpublished population is every live row — so the framework's
 * own keyset machinery executes with Hibernate's {@code @SoftDelete}
 * filter applied automatically.</p>
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

    /** Friendly precheck for the database-unique canonical typed option combination. */
    boolean existsByProductIdAndOptionCombinationKey(UUID productId, String optionCombinationKey);

    // ------------------------------------------------------------------
    // Campaign packet campaign-b5-a (the Saleor productVariants read
    // root): the ADMIN population's derived keyset scrolls — the
    // scrollStaffUsers precedent (Window-returning, KeysetScrollPosition/
    // Limit-taking, the static ordering baked into the method name with
    // the id tiebreaker). No population gate: the include-unpublished
    // population is every live row, so Hibernate's own @SoftDelete filter
    // and the framework's own keyset predicate carry the whole contract.
    // ------------------------------------------------------------------

    /** The ADMIN (include-unpublished) population's LAST_MODIFIED_AT-ascending keyset scroll. */
    Window<ProductVariant> findByOrderByUpdatedAtAscIdAsc(KeysetScrollPosition position, Limit limit);

    /** The ADMIN (include-unpublished) population's LAST_MODIFIED_AT-descending keyset scroll. */
    Window<ProductVariant> findByOrderByUpdatedAtDescIdDesc(KeysetScrollPosition position, Limit limit);

    /** The ADMIN (include-unpublished) population's ID-ascending keyset scroll (the unique key alone). */
    Window<ProductVariant> findByOrderByIdAsc(KeysetScrollPosition position, Limit limit);

    /** The ADMIN (include-unpublished) population's ID-descending keyset scroll (the unique key alone). */
    Window<ProductVariant> findByOrderByIdDesc(KeysetScrollPosition position, Limit limit);

    // ------------------------------------------------------------------
    // Campaign packet campaign-b5-a: the PUBLIC population's native
    // keyset scrolls — the two-row gate (variant ACTIVE AND parent
    // product ACTIVE) the reservation write itself carries, stated as
    // the parent EXISTS over the plain product_id column with BOTH
    // soft-delete filters explicit (native SQL gets no @SoftDelete).
    // The keyset predicate is the expanded row-value comparison over
    // the executed sort keys; :lastUpdatedAt/:lastId bind NULL on the
    // initial page so the predicate passes every row. Every NULL-bound
    // parameter carries an explicit PostgreSQL CAST — the documented
    // cure for the server's "could not determine data type of parameter"
    // preparation failure when a NULL arrives without an inferable type
    // (the CI-measured internal error on the initial page). Each method
    // fetches :fetchCount rows (the service passes pageSize+1) — a full
    // fetch is the database's own hasNext answer.
    // ------------------------------------------------------------------

    /** The public population's LAST_MODIFIED_AT-ascending keyset scroll (the two-row gate). */
    @Query(value = """
            SELECT v.* FROM product_variants v
             WHERE v.is_deleted = FALSE
               AND v.variant_status = 'ACTIVE'
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.product_status = 'ACTIVE'
                              AND p.is_deleted = FALSE)
               AND (CAST(:lastUpdatedAt AS timestamptz) IS NULL
                    OR v.updated_at > CAST(:lastUpdatedAt AS timestamptz)
                    OR (v.updated_at = CAST(:lastUpdatedAt AS timestamptz) AND v.id > CAST(:lastId AS uuid)))
             ORDER BY v.updated_at ASC, v.id ASC
             LIMIT :fetchCount
            """, nativeQuery = true)
    List<ProductVariant> scrollPublicByUpdatedAtAsc(
            @Param("lastUpdatedAt") java.time.OffsetDateTime lastUpdatedAt,
            @Param("lastId") UUID lastId,
            @Param("fetchCount") int fetchCount);

    /** The public population's LAST_MODIFIED_AT-descending keyset scroll (the two-row gate). */
    @Query(value = """
            SELECT v.* FROM product_variants v
             WHERE v.is_deleted = FALSE
               AND v.variant_status = 'ACTIVE'
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.product_status = 'ACTIVE'
                              AND p.is_deleted = FALSE)
               AND (CAST(:lastUpdatedAt AS timestamptz) IS NULL
                    OR v.updated_at < CAST(:lastUpdatedAt AS timestamptz)
                    OR (v.updated_at = CAST(:lastUpdatedAt AS timestamptz) AND v.id < CAST(:lastId AS uuid)))
             ORDER BY v.updated_at DESC, v.id DESC
             LIMIT :fetchCount
            """, nativeQuery = true)
    List<ProductVariant> scrollPublicByUpdatedAtDesc(
            @Param("lastUpdatedAt") java.time.OffsetDateTime lastUpdatedAt,
            @Param("lastId") UUID lastId,
            @Param("fetchCount") int fetchCount);

    /** The public population's ID-ascending keyset scroll (the two-row gate, the unique key alone). */
    @Query(value = """
            SELECT v.* FROM product_variants v
             WHERE v.is_deleted = FALSE
               AND v.variant_status = 'ACTIVE'
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.product_status = 'ACTIVE'
                              AND p.is_deleted = FALSE)
               AND (:lastId IS NULL OR v.id > :lastId)
             ORDER BY v.id ASC
             LIMIT :fetchCount
            """, nativeQuery = true)
    List<ProductVariant> scrollPublicByIdAsc(
            @Param("lastId") UUID lastId,
            @Param("fetchCount") int fetchCount);

    /** The public population's ID-descending keyset scroll (the two-row gate, the unique key alone). */
    @Query(value = """
            SELECT v.* FROM product_variants v
             WHERE v.is_deleted = FALSE
               AND v.variant_status = 'ACTIVE'
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.product_status = 'ACTIVE'
                              AND p.is_deleted = FALSE)
               AND (:lastId IS NULL OR v.id < :lastId)
             ORDER BY v.id DESC
             LIMIT :fetchCount
            """, nativeQuery = true)
    List<ProductVariant> scrollPublicByIdDesc(
            @Param("lastId") UUID lastId,
            @Param("fetchCount") int fetchCount);

    /** The public population's own count — the same two-row gate (the honest caller-scoped totalCount). */
    @Query(value = """
            SELECT COUNT(*) FROM product_variants v
             WHERE v.is_deleted = FALSE
               AND v.variant_status = 'ACTIVE'
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.product_status = 'ACTIVE'
                              AND p.is_deleted = FALSE)
            """, nativeQuery = true)
    long countPublicPopulation();

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
