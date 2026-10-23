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

    Optional<CmsPageType> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Window<CmsPageType> findAllByOrderByNameAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsPageType> findAllByOrderByNameDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<CmsPageType> findAllByOrderBySlugAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsPageType> findAllByOrderBySlugDescIdDesc(KeysetScrollPosition position, Limit limit);

    long count();
}
