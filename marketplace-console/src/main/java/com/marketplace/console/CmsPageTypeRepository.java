package com.marketplace.console;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Optional;
import java.util.UUID;

/** Agent 1: the CMS page type repository (derived queries + keyset windows). */
public interface CmsPageTypeRepository extends JpaRepository<CmsPageType, UUID>,
        RevisionRepository<CmsPageType, UUID, Integer> {

    /**
     * The 2026-11-05 #810 round review (Minor · Functional Correctness):
     * the (page type, position) assignment serialization seam — every
     * write path that reads the type's LAST assigned position and inserts
     * (assign, unassign + resequence, reorder) loads the type row
     * PESSIMISTIC_WRITE first, so two concurrent assignments can no longer
     * read the same last position and both insert at it (the schema does
     * not enforce uniqueness on (page_type_id, position) — display order
     * would silently fall back to the id tiebreaker). The official Spring
     * Data JPA locking derived query.
     */
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "select pageType from CmsPageType pageType where pageType.id = :id")
    Optional<CmsPageType> findByIdForUpdate(
            @org.springframework.data.repository.query.Param("id") UUID id);

    Optional<CmsPageType> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Window<CmsPageType> findAllByOrderByNameAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsPageType> findAllByOrderByNameDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<CmsPageType> findAllByOrderBySlugAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsPageType> findAllByOrderBySlugDescIdDesc(KeysetScrollPosition position, Limit limit);

    long count();
}
