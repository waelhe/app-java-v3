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

    List<CmsMenuItem> findAllByMenuIdAndParentIdOrderByPositionAscIdAsc(UUID menuId, UUID parentId);

    List<CmsMenuItem> findAllByMenuIdAndParentIdIsNullOrderByPositionAscIdAsc(UUID menuId);

    Optional<CmsMenuItem> findByMenuIdAndNameAndParentIdIsNull(UUID menuId, String name);

    long countByMenuId(UUID menuId);

    boolean existsByPageId(UUID pageId);

    Window<CmsMenuItem> findAllByOrderByNameAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CmsMenuItem> findAllByOrderByNameDescIdDesc(KeysetScrollPosition position, Limit limit);
}
