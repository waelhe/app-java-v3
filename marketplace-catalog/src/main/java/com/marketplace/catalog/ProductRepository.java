package com.marketplace.catalog;

import java.util.UUID;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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
