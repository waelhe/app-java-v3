package com.marketplace.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence boundary for reusable ProductType reference data. */
public interface ProductTypeRepository extends JpaRepository<ProductType, UUID> {

    Optional<ProductType> findByCode(String code);

    List<ProductType> findAllByOrderByCodeAsc();

    /**
     * B21 (CAT-09 — the 2026-11-03 adversarial review, review-pr-808): the
     * CREATE side of the TOCTOU serialization. A plain {@code findById}
     * releases nothing — the row stays readable by a concurrent
     * {@code productTypeDelete} between the create's existence check and
     * its commit, and the soft delete (an {@code is_deleted} UPDATE) leaves
     * the physical row the FK still accepts: a product silently referencing
     * a type the admin surface no longer shows. {@code FOR SHARE} (JPA's
     * {@link LockModeType#PESSIMISTIC_READ}, the documented Spring Data
     * JPA pessimistic-lock mechanism — the {@code findByIdForUpdate}
     * precedent's read twin) holds the row lock for the CREATING
     * transaction's lifetime: the concurrent delete's UPDATE blocks until
     * the create commits, so the FK the create validated is the FK the
     * create gets. The delete side takes the write twin
     * ({@link #findByIdForUpdate}) BEFORE its live-references guard, so the
     * pair serializes both ways.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select t from ProductType t where t.id = :id")
    Optional<ProductType> findByIdForShare(@Param("id") UUID id);

    /**
     * B21 (CAT-09): the DELETE side — the write twin of the orders
     * module's own {@code findByIdForUpdate} precedent. The row lock is
     * taken BEFORE the live-references guard runs, so a concurrent
     * create's FOR SHARE blocks this FOR UPDATE until the create commits;
     * the guard's count then sees the new product and answers the
     * classified INVALID refusal. The other order (delete locks first)
     * leaves the create's FOR SHARE waiting on a committed soft delete —
     * which the soft-delete filter then reads as the honest absence, the
     * classified NOT_FOUND.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ProductType t where t.id = :id")
    Optional<ProductType> findByIdForUpdate(@Param("id") UUID id);
}
