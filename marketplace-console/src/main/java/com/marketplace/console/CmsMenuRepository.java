package com.marketplace.console;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Optional;
import java.util.UUID;

/** Agent 1: the navigation menu repository (derived queries + keyset windows). */
public interface CmsMenuRepository extends JpaRepository<CmsMenu, UUID>,
        RevisionRepository<CmsMenu, UUID, Integer> {

    Optional<CmsMenu> findBySlug(String slug);

    Optional<CmsMenu> findByName(String name);

    boolean existsBySlug(String slug);

    Window<CmsMenu> findAllByOrderByNameAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsMenu> findAllByOrderByNameDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<CmsMenu> findAllByOrderBySlugAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsMenu> findAllByOrderBySlugDescIdDesc(KeysetScrollPosition position, Limit limit);

    long count();
}
