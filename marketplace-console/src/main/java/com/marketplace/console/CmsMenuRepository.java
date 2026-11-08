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

    /**
     * The 2026-11-08 #810 round-5 review (Minor · Data Integrity): the
     * menu row's locked load — the slot assignment (assignNavigation),
     * the menu's own delete, and createMenuItem's menu-existence leg all
     * serialize on this lock, so a menu cannot die between one path's
     * existence check and another's write (a slot pinned to a dead menu,
     * an item inserted for a deleted menu). The official Spring Data JPA
     * @Lock derived-query contract — the house findByIdForUpdate family.
     */
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from CmsMenu m where m.id = :id")
    Optional<CmsMenu> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    /**
     * The 2026-11-05 #810 round review (Minor · Functional Correctness):
     * two menus MAY share a name when their slugs differ (the uniqueness
     * law is the slug's) — the lookup answers ONE row by the deterministic
     * (name, id) order instead of the derived query's non-unique failure.
     */
    Optional<CmsMenu> findFirstByNameOrderByIdAsc(String name);

    boolean existsBySlug(String slug);

    Window<CmsMenu> findAllByOrderByNameAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsMenu> findAllByOrderByNameDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<CmsMenu> findAllByOrderBySlugAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsMenu> findAllByOrderBySlugDescIdDesc(KeysetScrollPosition position, Limit limit);

    long count();
}
