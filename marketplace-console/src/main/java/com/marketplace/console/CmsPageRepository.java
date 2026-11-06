package com.marketplace.console;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * CMS page repository — derived queries per the official Spring Data JPA
 * contract, including the forward-only keyset window methods the
 * {@code pages} root executes.
 *
 * <p><b>Derived-query property resolution (the CodeRabbit Critical round,
 * measured against Spring Data JPA 4.1.1's part-tree contract):</b> the
 * entity's JavaBean property is {@code published} (the {@code isPublished()}
 * reader); {@code IsPublished} in a derived name resolves to a property
 * named {@code isPublished}, which does not exist — the bootstrap-time
 * {@code QueryCreationException} kills the whole context. The declared-but-
 * never-called {@code findByIsPublishedTrue}/
 * {@code findByIsPublishedOrderBySlugAscIdAsc}/{@code countByIsPublishedTrue}
 * shapes were removed for exactly that reason: dead declarations that
 * broke startup. Every surviving derived name resolves against the real
 * property vocabulary ({@code slug}, {@code pageTypeId}, {@code title},
 * {@code createdAt}, {@code id}).</p>
 */
public interface CmsPageRepository extends JpaRepository<CmsPage, UUID>,
        RevisionRepository<CmsPage, UUID, Integer> {

    /**
     * The 2026-11-05 #810 round review (Minor · Functional Correctness):
     * the delete-vs-link serialization seam — BOTH write paths that touch
     * the page-reference relationship (the page's own delete, and a menu
     * item linking the page) load the page row PESSIMISTIC_WRITE first,
     * so the delete's reference check and the link's insert can never
     * interleave into a committed orphan (a live menu item pointing at a
     * soft-deleted page). The official Spring Data JPA locking derived
     * query (@Lock on a repository method).
     */
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "select page from CmsPage page where page.id = :id")
    Optional<CmsPage> findByIdForUpdate(@org.springframework.data.repository.query.Param("id")
                                        UUID id);

    Optional<CmsPage> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsByPageTypeId(UUID pageTypeId);

    Window<CmsPage> findAllByOrderBySlugAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsPage> findAllByOrderBySlugDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<CmsPage> findAllByOrderByTitleAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsPage> findAllByOrderByTitleDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<CmsPage> findAllByOrderByCreatedAtAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsPage> findAllByOrderByCreatedAtDescIdDesc(KeysetScrollPosition position, Limit limit);
}
