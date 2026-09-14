package com.marketplace.search;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts). Soft-deleted
 * rows are filtered by Hibernate's {@code @SoftDelete} on every derived
 * query.
 */
public interface SavedSearchRepository extends JpaRepository<SavedSearch, UUID> {

    /**
     * The owner's /me listing — the service passes the deterministic sort
     * (created_at DESC, id DESC: the stable-order L32 lesson).
     */
    @org.springframework.data.jpa.repository.Query("""
            select s from SavedSearch s
            where s.userId = :userId
            order by s.createdAt desc, s.id desc
            """)
    Page<SavedSearch> findByOwner(@org.springframework.data.repository.query.Param("userId") UUID userId,
                                  Pageable pageable);

    /**
     * The matcher's scan — every alert-enabled live saved search, paged in
     * id order (the deterministic scan cursor: a retry resumes exactly
     * where the id ordering left the page boundaries).
     */
    @org.springframework.data.jpa.repository.Query("""
            select s from SavedSearch s
            where s.alertEnabled = true
            order by s.id asc
            """)
    Page<SavedSearch> findAllAlertEnabled(Pageable pageable);
}
