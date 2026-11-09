package com.marketplace.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
 * forward-only keyset scrolling, never an offset. The ADMIN population's
 * scrolls (the 2026-11-06 #808 round review, Minor · Data Integrity &amp;
 * Integration) are the SAME native shape with the ADMIN's own gate: the
 * parent EXISTS keeps the include-unpublished LIFECYCLE widening (no
 * variant or product status filter) but requires the parent row to
 * RESOLVE LIVE — the derived {@code Window} methods the path rode had
 * NO gate at all, so a concurrent product delete could orphan a whole
 * page's worth of variants and the service's parent-resolution skip
 * answered an EMPTY page with {@code hasNext=true} and no usable scroll
 * position (an uncontinuable scroll), while the path's {@code count()}
 * counted the orphans the population can never render. The parent-live
 * EXISTS at the QUERY is the exclusion the finding demands — BEFORE the
 * page is cut — and the population's own count rides the identical
 * predicate (the honest caller-scoped total). The belt-and-braces
 * parent-null skip stays in the service for the vanish window BETWEEN
 * the page query and the parent projections load.</p>
 */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    /**
     * The SKU namespace probe — the seller's own space only (the V219
     * {@code uq_product_variants_provider_sku} index's read side; the
     * index itself is the race backstop, this read is the friendly
     * pre-check that answers the house 409 with the collision named).
     */
    Optional<ProductVariant> findByProviderIdAndSku(UUID providerId, String sku);

    /**
     * The 2026-11-06 #808 round review (Minor · Data Integrity): the
     * SKU reservation spans the RAW table — V219's unique index is
     * GLOBAL (not partial over the live rows), so a soft-deleted
     * variant keeps reserving its SKU while the derived probe above
     * (filtered to live rows) misses it and the write dies on the raw
     * constraint error instead of the classified ALREADY_EXISTS. The
     * native count bypasses the soft-delete filter the derived query
     * carries.
     */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT COUNT(*) FROM product_variants WHERE provider_id = :providerId AND sku = :sku",
            nativeQuery = true)
    long countSkuReservationsIncludingDeleted(
            @org.springframework.data.repository.query.Param("providerId") UUID providerId,
            @org.springframework.data.repository.query.Param("sku") String sku);

    /** The update leg's own reservation count — the addressed row itself excluded. */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT COUNT(*) FROM product_variants WHERE provider_id = :providerId AND sku = :sku"
                    + " AND id <> :excludeId",
            nativeQuery = true)
    long countSkuReservationsIncludingDeletedExcluding(
            @org.springframework.data.repository.query.Param("providerId") UUID providerId,
            @org.springframework.data.repository.query.Param("sku") String sku,
            @org.springframework.data.repository.query.Param("excludeId") UUID excludeId);

    /** The product's live roster (the owner's list surface — the V219 partial index's read). */
    List<ProductVariant> findByProductIdOrderBySkuAsc(UUID productId);

    /**
     * The 2026-11-09 #808 round-26 review (Minor · Stability &amp;
     * Availability): the roster's own FOR-UPDATE twin — the assignment
     * scope move's consistency walk loads a product's variants,
     * re-derives their combination keys over the post-move schema, and
     * the SAME transaction then SAVES those variants: a concurrent
     * variant writer (a price change, a value edit) that committed
     * while the walk's transaction waited left the walked rows'
     * {@code @Version} stale, and the recombination save answered the
     * raw OptimisticLockException — the whole scope move rolled back
     * unclassified. The locked roster serializes the pair (the
     * setStock/deleteVariants ownedRowForUpdate discipline, hoisted to
     * the whole roster the move re-keys).
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.productId = :productId order by v.sku asc")
    List<ProductVariant> findByProductIdOrderBySkuAscForUpdate(@Param("productId") UUID productId);

    /**
     * B7's TOCTOU residual (CAT-04 — the 2026-11-04 follow-on): the
     * delete-path locked roster, the money path's own
     * {@code VoucherRepository.findByCodeForUpdate} pattern. The variant
     * reservation ({@link #reserve}) is a NATIVE UPDATE on the VARIANT
     * row alone — its parent-product gate rides a NON-locking EXISTS, and
     * a variant cart line never increments the parent product's
     * reserved counter (ADR-0012's placement splits the two shelves) —
     * so the PRODUCT row's pessimistic lock cannot close the delete
     * race for variant reservations: the variant rows carry their own.
     * The locked roster read serializes the delete's variant guard +
     * cascade against a concurrent variant reservation: either the
     * delete holds the rows first and the reservation's
     * {@code is_deleted = FALSE} predicate matches ZERO rows (the
     * placement's own honest 409, rolled back whole), or the reservation
     * committed first and the locked roster sees its counter and the
     * delete refuses.
     *
     * <p>The 2026-11-08 #808 round-21 review (Minor · Stability &amp;
     * Availability, additional comment) locked the roster in the row-ID
     * order; the 2026-11-08 #808 round-22 review corrected the law it
     * claimed: this query locks the rows in the DATABASE's own UUID
     * order (PostgreSQL compares uuid by memcmp, unsigned bytes), and
     * the Java-side bulk passes must sort by the same order — the
     * {@code ProductVariantWriteService#VARIANT_LOCK_ORDER} hex-string
     * comparator. Java's natural UUID order (signed-long halves) is a
     * DIFFERENT order on half of all UUIDs, so the round-8/round-21
     * claim that both sides used "one sorted-UUID order" was never
     * actually true — a product delete's roster and a bulk variant
     * delete could still hold one variant each and wait for the other's
     * (the database's own deadlock abort). The display order was never
     * load-bearing here: the guard scans every row and the cascade
     * deletes the same set either way.</p>
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.productId = :productId order by v.id asc")
    List<ProductVariant> findByProductIdOrderByIdAscForUpdate(@Param("productId") UUID productId);

    /**
     * The single-row locked read for the DELETE paths (the 2026-11-05
     * #808 round, Major · Functional Correctness): the variant
     * reservation is a native UPDATE on the variant row alone, so the
     * row's own pessimistic lock serializes the delete's
     * guard-plus-soft-delete against a concurrent placement — the same
     * money-path pattern ({@code VoucherRepository.findByCodeForUpdate})
     * the product delete's wall rides.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.id = :id")
    Optional<ProductVariant> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The 2026-11-08 #808 round-21 review (Minor · Stability &amp;
     * Availability, additional comment): the OWNERSHIP-SCOPED locked
     * read — the provider predicate rides INSIDE the locking query (a
     * skipped foreign row never locks; the ProductRepository
     * .findByIdAndProviderIdForUpdate precedent verbatim).
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariant v where v.id = :id and v.providerId = :providerId")
    Optional<ProductVariant> findByIdAndProviderIdForUpdate(
            @Param("id") UUID id, @Param("providerId") UUID providerId);

    /**
     * The 2026-11-07 #808 round-19 review (Minor · Data Integrity &amp;
     * Integration): the bulk-update path's membership probe — a PROJECTION
     * (never the entity): the previous unlocked {@code findById} loaded
     * the row into the persistence context BEFORE the single path's
     * {@code findByIdForUpdate} took the row lock, and a locking query on
     * an already-managed entity returns the CACHED pre-lock state (the
     * round-11 drop-id/lock/load discipline, applied to the bulk shape).
     * The derived boolean also keeps the safe-absence vocabulary
     * uniform: a missing row and a foreign-parented row answer the same
     * verdict the single path's ownedRow owns.
     */
    boolean existsByIdAndProductId(UUID id, UUID productId);

    /** Friendly precheck for the database-unique canonical typed option combination. */
    boolean existsByProductIdAndOptionCombinationKey(UUID productId, String optionCombinationKey);

    /**
     * The 2026-11-06 #808 round-3 review (Major · Data Integrity &amp;
     * Integration), widened by the round-5 review: the
     * new-selection-assignment guard's probe — whether a product on the
     * published shelf (ACTIVE, or SUSPENDED — the state
     * {@code reactivate()} returns to ACTIVE without re-running the
     * publication gate) carries ANY live variant. The round-5 widening:
     * the NULL-KEY (free-form) variant is the same hole the TYPED one
     * named — a selection-free type publishes with a free-form active
     * variant, the selection assign then slips past a key-only probe, and
     * the shelf never re-passes the publication gate to surface the
     * variant's missing selection value. Every live variant of a
     * published-shelf product (typed or free-form) refuses the assign
     * until the variants migrate (the attribute-delete wall's own law).
     * DRAFT products stay eligible: the publication gate owns their
     * coverage verdict at publish time.
     */
    @Query("""
            select (count(v) > 0) from ProductVariant v
              join Product p on p.id = v.productId
             where p.productTypeId = :productTypeId
               and p.status in (com.marketplace.catalog.StoreProductStatus.ACTIVE,
                                com.marketplace.catalog.StoreProductStatus.SUSPENDED)
               and v.status <> com.marketplace.catalog.ProductStatus.ARCHIVED
            """)
    boolean existsPublishedShelfVariantForType(@Param("productTypeId") UUID productTypeId);

    // ------------------------------------------------------------------
    // Campaign packet campaign-b5-a: the ADMIN population's native
    // keyset scrolls — the parent-live EXISTS gate (the 2026-11-06 #808
    // round review, Minor · Data Integrity): the include-unpublished
    // population widens lifecycle states, NOT integrity — a variant whose
    // parent product no longer resolves live is not a renderable roster
    // member and never reaches the page cut. The gate spans TWO rows
    // (the variant's own live shelf AND the parent's live shelf, ANY
    // product status), so the scroll is NATIVE like the public twins:
    // the parent EXISTS over the plain product_id column with BOTH
    // soft-delete filters explicit. The keyset predicate is the expanded
    // row-value comparison over the executed sort keys with the id
    // tiebreaker; :lastUpdatedAt/:lastId bind NULL on the initial page
    // (the IS NULL occurrences carry the explicit casts — the wave-19
    // review's own fix). Each method fetches :fetchCount rows (the
    // service passes pageSize+1) — a full fetch is the database's own
    // hasNext answer.
    // ------------------------------------------------------------------

    /** The ADMIN population's LAST_MODIFIED_AT-ascending keyset scroll (the parent-live gate). */
    @Query(value = """
            SELECT v.* FROM product_variants v
             WHERE v.is_deleted = FALSE
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.is_deleted = FALSE)
               AND (CAST(:lastUpdatedAt AS timestamptz) IS NULL
                    OR v.updated_at > :lastUpdatedAt
                    OR (v.updated_at = :lastUpdatedAt AND v.id > :lastId))
             ORDER BY v.updated_at ASC, v.id ASC
             LIMIT :fetchCount
            """, nativeQuery = true)
    List<ProductVariant> scrollAdminByUpdatedAtAsc(
            @Param("lastUpdatedAt") java.time.OffsetDateTime lastUpdatedAt,
            @Param("lastId") UUID lastId,
            @Param("fetchCount") int fetchCount);

    /** The ADMIN population's LAST_MODIFIED_AT-descending keyset scroll (the parent-live gate). */
    @Query(value = """
            SELECT v.* FROM product_variants v
             WHERE v.is_deleted = FALSE
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.is_deleted = FALSE)
               AND (CAST(:lastUpdatedAt AS timestamptz) IS NULL
                    OR v.updated_at < :lastUpdatedAt
                    OR (v.updated_at = :lastUpdatedAt AND v.id < :lastId))
             ORDER BY v.updated_at DESC, v.id DESC
             LIMIT :fetchCount
            """, nativeQuery = true)
    List<ProductVariant> scrollAdminByUpdatedAtDesc(
            @Param("lastUpdatedAt") java.time.OffsetDateTime lastUpdatedAt,
            @Param("lastId") UUID lastId,
            @Param("fetchCount") int fetchCount);

    /** The ADMIN population's ID-ascending keyset scroll (the parent-live gate, the unique key alone). */
    @Query(value = """
            SELECT v.* FROM product_variants v
             WHERE v.is_deleted = FALSE
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.is_deleted = FALSE)
               AND (CAST(:lastId AS uuid) IS NULL OR v.id > :lastId)
             ORDER BY v.id ASC
             LIMIT :fetchCount
            """, nativeQuery = true)
    List<ProductVariant> scrollAdminByIdAsc(
            @Param("lastId") UUID lastId,
            @Param("fetchCount") int fetchCount);

    /** The ADMIN population's ID-descending keyset scroll (the parent-live gate, the unique key alone). */
    @Query(value = """
            SELECT v.* FROM product_variants v
             WHERE v.is_deleted = FALSE
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.is_deleted = FALSE)
               AND (CAST(:lastId AS uuid) IS NULL OR v.id < :lastId)
             ORDER BY v.id DESC
             LIMIT :fetchCount
            """, nativeQuery = true)
    List<ProductVariant> scrollAdminByIdDesc(
            @Param("lastId") UUID lastId,
            @Param("fetchCount") int fetchCount);

    /** The ADMIN population's own count — the SAME parent-live gate (the honest caller-scoped totalCount). */
    @Query(value = """
            SELECT COUNT(*) FROM product_variants v
             WHERE v.is_deleted = FALSE
               AND EXISTS (SELECT 1
                             FROM products p
                            WHERE p.id = v.product_id
                              AND p.is_deleted = FALSE)
            """, nativeQuery = true)
    long countAdminPopulation();

    // ------------------------------------------------------------------
    // Campaign packet campaign-b5-a: the PUBLIC population's native
    // keyset scrolls — the two-row gate (variant ACTIVE AND parent
    // product ACTIVE) the reservation write itself carries, stated as
    // the parent EXISTS over the plain product_id column with BOTH
    // soft-delete filters explicit (native SQL gets no @SoftDelete).
    // The keyset predicate is the expanded row-value comparison over
    // the executed sort keys; :lastUpdatedAt/:lastId bind NULL on the
    // initial page so the predicate passes every row — the IS NULL
    // occurrences carry the explicit uuid CAST (the wave-19 #808
    // review: the untyped null bind against the uuid column could fail
    // the first page before the query even executes). Each method
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
                    OR v.updated_at > :lastUpdatedAt
                    OR (v.updated_at = :lastUpdatedAt AND v.id > :lastId))
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
                    OR v.updated_at < :lastUpdatedAt
                    OR (v.updated_at = :lastUpdatedAt AND v.id < :lastId))
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
               AND (CAST(:lastId AS uuid) IS NULL OR v.id > :lastId)
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
               AND (CAST(:lastId AS uuid) IS NULL OR v.id < :lastId)
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
