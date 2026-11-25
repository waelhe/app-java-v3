package com.marketplace.console;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1: the menu item repository (tree reads + position updates + the
 * forward-only keyset window the {@code menuItems} root executes — the
 * same derived-query shape {@code CmsMenuRepository}/
 * {@code CmsPageRepository} carry, added by the CodeRabbit pagination
 * round: the whole-collection {@code (name, id)} sorts, both directions).
 */
public interface CmsMenuItemRepository extends JpaRepository<CmsMenuItem, UUID>,
        RevisionRepository<CmsMenuItem, UUID, Integer> {

    List<CmsMenuItem> findAllByMenuIdOrderByPositionAscIdAsc(UUID menuId);

    /**
     * The 2026-11-08 #810 round-5 review (Minor · Data Integrity): the
     * item row's locked load — createMenuItem locks the PARENT it inserts
     * under, and deleteMenuItem locks the row it deletes (the subtree's
     * promotion source), so a child cannot be inserted under a parent
     * that is concurrently dying (the create either precedes the delete
     * and its row joins the promoted subtree, or waits and answers the
     * honest 404). The official Spring Data JPA @Lock derived-query
     * contract — the house findByIdForUpdate family.
     */
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "select i from CmsMenuItem i where i.id = :id")
    Optional<CmsMenuItem> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    List<CmsMenuItem> findAllByMenuIdAndParentIdOrderByPositionAscIdAsc(UUID menuId, UUID parentId);

    List<CmsMenuItem> findAllByMenuIdAndParentIdIsNullOrderByPositionAscIdAsc(UUID menuId);

    Optional<CmsMenuItem> findByMenuIdAndNameAndParentIdIsNull(UUID menuId, String name);

    long countByMenuId(UUID menuId);

    boolean existsByPageId(UUID pageId);

    Window<CmsMenuItem> findAllByOrderByNameAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsMenuItem> findAllByOrderByNameDescIdDesc(KeysetScrollPosition position, Limit limit);
}
