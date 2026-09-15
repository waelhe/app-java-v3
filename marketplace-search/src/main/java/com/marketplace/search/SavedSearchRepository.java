package com.marketplace.search;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts). Soft-deleted
 * rows are filtered by Hibernate's {@code @SoftDelete} on every query.
 *
 * <p><b>The scan is KEYSET-paginated</b> (CodeRabbit round 1): offset pages
 * recalculate their window against the live alert-enabled set — a concurrent
 * soft delete shifts rows left and the next offset silently skips an
 * unrelated saved search (a missed alert, not a retry). The keyset form
 * ({@code id > :after ORDER BY id}) is immune to shifting: each row is
 * visited at most once per scan regardless of what other transactions do
 * to earlier pages.
 */
public interface SavedSearchRepository extends JpaRepository<SavedSearch, UUID> {

    /**
     * The owner's /me listing — the deterministic order (created_at DESC,
     * id DESC: the stable-order L32 lesson).
     */
    @Query("""
            select s from SavedSearch s
            where s.userId = :userId
            order by s.createdAt desc, s.id desc
            """)
    org.springframework.data.domain.Page<SavedSearch> findByOwner(@Param("userId") UUID userId,
                                                                  Pageable pageable);

    /**
     * The matcher's keyset scan — every alert-enabled live saved search in
     * id order, one batch of {@code pageable.getPageSize()} rows. A
     * {@link Slice} (no count query) — the scan only needs "hasNext". Two
     * explicit forms instead of a nullable {@code :after is null} JPQL
     * predicate — the null binding is driver-fragile, and the first batch
     * needs no predicate at all.
     */
    @Query("""
            select s from SavedSearch s
            where s.alertEnabled = true
            order by s.id asc
            """)
    Slice<SavedSearch> findFirstAlertEnabledBatch(Pageable pageable);

    /** The keyset form — strictly after {@code after}, same order. */
    @Query("""
            select s from SavedSearch s
            where s.alertEnabled = true
              and s.id > :after
            order by s.id asc
            """)
    Slice<SavedSearch> findAlertEnabledAfter(@Param("after") UUID after, Pageable pageable);

    /** The per-user cap check (the create-time availability bound). */
    long countByUserId(UUID userId);
}
