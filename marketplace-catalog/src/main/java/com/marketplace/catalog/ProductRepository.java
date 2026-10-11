package com.marketplace.catalog;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
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
public interface ProductRepository extends JpaRepository<Product, UUID> {

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
               and p.status = com.marketplace.catalog.ProductStatus.ACTIVE
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
}
