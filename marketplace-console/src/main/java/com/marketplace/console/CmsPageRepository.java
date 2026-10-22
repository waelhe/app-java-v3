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
 */
public interface CmsPageRepository extends JpaRepository<CmsPage, UUID>,
        RevisionRepository<CmsPage, UUID, Integer> {

    Optional<CmsPage> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsByPageTypeId(UUID pageTypeId);

    Window<CmsPage> findAllByOrderBySlugAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsPage> findAllByOrderBySlugDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<CmsPage> findAllByOrderByTitleAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsPage> findAllByOrderByTitleDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<CmsPage> findAllByOrderByCreatedAtAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsPage> findAllByOrderByCreatedAtDescIdDesc(KeysetScrollPosition position, Limit limit);

    long count();
}
