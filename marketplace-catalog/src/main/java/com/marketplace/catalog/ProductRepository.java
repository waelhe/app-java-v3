package com.marketplace.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * A-17 (C.7): the M1 store root's repository. The {@code ProductLookupAdapter}
 * resolves media targets through {@link JpaRepository#findById} — the
 * soft-delete filter rides {@code BaseEntity}'s {@code @SoftDelete}, so a
 * deleted product is not a media target (the port's 404 contract).
 *
 * <p>Stage 6 (ADR-0002): the inventory's atomic writes — one conditional
 * modifying UPDATE per call (the Spring Data JPA-official mechanism; the
 * PostgreSQL row lock is the concurrency). The {@code WHERE} carries the
 * whole invariant (ACTIVE state, the free stock, the reserved set), so a
 * zero-updated-rows answer IS the refusal — the adapter turns it into the
 * house 409. No read-modify-write exists anywhere on these paths.
 */
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    /** Owner-scoped roster for the legacy REST list. */
    java.util.List<Product> findByProviderId(UUID providerId);

    /**
     * The type's live products (the {@code @SoftDelete} filter rides the
     * derived query automatically) — the 2026-11-06 #808 round review's
     * assignment-scope move guard (Major · Data Integrity &amp;
     * Integration): the variant-consistency verdict walks every product
     * whose variants the scope move can re-key, so the move's refusal or
     * completion is decided over the AFFECTED population itself, never a
     * sample.
     */
    java.util.List<Product> findByProductTypeId(UUID productTypeId);

    /** Bounded owner-scoped roster for the existing offset/page contract. */
    org.springframework.data.domain.Page<Product> findByProviderId(
            UUID providerId, org.springframework.data.domain.Pageable pageable);

    /**
     * Keyset-backed owner roster for the Saleor-aligned catalog Connection.
     * The non-null creation timestamp plus unique id form the stable sort;
     * the matching partial index keeps continuation queries bounded.
     */
    Window<Product> findByProviderIdOrderByCreatedAtDescIdDesc(
            UUID providerId, KeysetScrollPosition position, Limit limit);

    // ------------------------------------------------------------------
    // Campaign packet campaign-b4-c (the Saleor products read roots): the
    // store-wide SQL keyset scrolls — the scrollStaffUsers derived-method
    // precedent (Window-returning, KeysetScrollPosition/Limit-taking, the
    // static ordering baked into the method name with the id tiebreaker).
    // The products table is populated commerce data, never bounded
    // reference data: the continuation queries execute as real keyset SQL.
    // ------------------------------------------------------------------

    /** The include-unpublished (ADMIN) population's NAME-ascending keyset scroll. */
    Window<Product> findByOrderByTitleAscIdAsc(KeysetScrollPosition position, Limit limit);

    /** The include-unpublished (ADMIN) population's NAME-descending keyset scroll. */
    Window<Product> findByOrderByTitleDescIdDesc(KeysetScrollPosition position, Limit limit);

    /** The public ACTIVE population's NAME-ascending keyset scroll (the storefront slice). */
    Window<Product> findByStatusOrderByTitleAscIdAsc(
            StoreProductStatus status, KeysetScrollPosition position, Limit limit);

    /** The public ACTIVE population's NAME-descending keyset scroll (the storefront slice). */
    Window<Product> findByStatusOrderByTitleDescIdDesc(
            StoreProductStatus status, KeysetScrollPosition position, Limit limit);

    /** The public ACTIVE population's own count (the honest caller-scoped totalCount). */
    long countByStatus(StoreProductStatus status);

    /**
     * The goods-schema's live-product probe — the product-type delete
     * gate's own read (the wave-19 #808 review: a schema with products
     * answers the classified INVALID, never an orphaning delete).
     */
    boolean existsByProductTypeId(UUID productTypeId);

    /**
     * B7's TOCTOU residual (CAT-04 — the 2026-11-04 follow-on): the
     * delete-path locked read, the money path's own
     * {@code VoucherRepository.findByCodeForUpdate} pattern verbatim. The
     * reservation machinery's bulk UPDATE ({@link #reserve}) writes THIS
     * row but never touches the {@code @Version} column, so a guard on an
     * unlocked {@code findById} is check-then-act: a delete that passed on
     * reserved=0 could soft-delete the row while a concurrent placement's
     * reservation commits — the exact stranded U1 state the B7 wall was
     * built to prevent. The pessimistic write lock serializes the delete's
     * guard+soft-delete against that UPDATE: either the delete holds the
     * row first and the reservation's soft-delete-filtered UPDATE matches
     * ZERO rows (the placement's own honest 409, rolled back whole), or
     * the reservation committed first and the locked read sees the
     * reserved counter and the delete refuses.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The 2026-11-08 #808 round-21 review (Minor · Stability &amp;
     * Availability, additional comment): the OWNERSHIP-SCOPED locked
     * read — the provider predicate rides INSIDE the locking query, so a
     * skipped foreign row never locks (the previous lock-then-filter
     * shape held the WRITE lock on another provider's row for the whole
     * batch duration, blocking its reservations and legitimate writes).
     * The same locked-read law the delete wall owns, scoped to the
     * caller's own shelf from the first statement.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id and p.providerId = :providerId")
    Optional<Product> findByIdAndProviderIdForUpdate(
            @Param("id") UUID id, @Param("providerId") UUID providerId);

    /**
     * The 2026-11-08 #808 round-25 review (Minor · Stability &amp;
     * Availability): the FOR-SHARE twin of the ownership-scoped locked
     * read — the provider predicate rides INSIDE the share-locking query
     * (the {@code findByIdAndProviderIdForUpdate} round-21 precedent
     * verbatim), so a skipped foreign row never locks: a provider naming
     * another provider's product answers the safe absence without
     * waiting on the stranger's row, while the caller's OWN rows keep
     * the reference-creating share serialization the registration paths
     * exist to carry.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @Query("select p from Product p where p.id = :id and p.providerId = :providerId")
    Optional<Product> findByIdAndProviderIdForShare(
            @Param("id") UUID id, @Param("providerId") UUID providerId);

    /**
     * The 2026-11-07 #808 round-19 review (Minor · Data Integrity &amp;
     * Integration): the reference-CREATING read's FOR-SHARE twin (the
     * {@code ProductTypeRepository.findByIdForShare} precedent verbatim)
     * — the collection membership insert and every other reference
     * creator holds the product row for ITS transaction's lifetime, so
     * the delete's FOR-UPDATE row lock plus its membership cascade
     * serialize against the insert: either the delete committed first
     * (this read answers the soft-deleted row's absence) or the insert
     * commits its reference first and the delete's cascade carries it
     * away with the product.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForShare(@Param("id") UUID id);

    /**
     * The 2026-11-09 #808 round-26 review (Minor · Performance &amp;
     * Scalability): the reference-creating read's own BATCH twin — the
     * collection append's liveness re-read used to take the per-product
     * FOR-SHARE row one id at a time (N locking statements inside the
     * collection's advisory-lock window, stretching every membership
     * write's critical section with the request's size). ONE statement
     * takes every member row's share lock; the vanished ids answer from
     * the returned set's own diff (the same ids, the same locks, the
     * same serialized-against-the-delete pairing the single read owns —
     * the delete's FOR-UPDATE lock and its cascade still serialize with
     * this read over the SAME rows).
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @Query("select p from Product p where p.id in :ids")
    List<Product> findAllByIdForShare(@Param("ids") Collection<UUID> ids);

    /**
     * Reserves {@code qty} units of an ACTIVE product whose free stock
     * covers it — the placement's write.
     *
     * @return 1 when the reservation landed, 0 when the product is not
     *         ACTIVE or lacks the free stock (the caller's whole-map
     *         reservation rolls back with the refusal)
     */
    @Modifying
    @Query("""
            update Product p
               set p.reservedQuantity = p.reservedQuantity + :qty
             where p.id = :id
               and p.status = com.marketplace.catalog.StoreProductStatus.ACTIVE
               and (p.stockQuantity - p.reservedQuantity) >= :qty
            """)
    int reserve(@Param("id") UUID id, @Param("qty") int qty);

    /**
     * Releases {@code qty} reserved units — the cancellation's write.
     *
     * @return 1 when the release landed, 0 when the product holds less
     *         than the quantity reserved (never expected on the machine's
     *         guarded path; a zero answer surfaces the inconsistency)
     */
    @Modifying
    @Query("""
            update Product p
               set p.reservedQuantity = p.reservedQuantity - :qty
             where p.id = :id
               and p.reservedQuantity >= :qty
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
            update Product p
               set p.stockQuantity = p.stockQuantity - :qty,
                   p.reservedQuantity = p.reservedQuantity - :qty
             where p.id = :id
               and p.reservedQuantity >= :qty
               and p.stockQuantity >= :qty
            """)
    int commit(@Param("id") UUID id, @Param("qty") int qty);

    /**
     * Restocks {@code qty} units — the return's write (the V225 RMA
     * lifecycle): the received goods go back on the shelf. An UNCONDITIONAL
     * increment by design: the goods physically returned whatever the
     * product's display lifecycle says (a suspended product's returns still
     * restock — the shelf count is the physical truth, the status only
     * gates buying).
     */
    @Modifying
    @Query("""
            update Product p
               set p.stockQuantity = p.stockQuantity + :qty
             where p.id = :id
            """)
    int restock(@Param("id") UUID id, @Param("qty") int qty);
}
