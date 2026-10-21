package com.marketplace.console;

import com.marketplace.shared.api.CmsNavigationPort;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-CMS-001 ownership decision): the console module's navigation owning
 * use case — the {@code assignNavigation}/{@code menu*}/{@code menuItem*}
 * mutations and reads behind the {@link CmsNavigationPort} seam.
 * ADMIN-enforced at this boundary (Saleor requires MANAGE_MENUS).
 *
 * <p><b>The tree invariants, both enforced here:</b> a parent edge never
 * crosses menus (an item's parent belongs to the SAME menu — the V283
 * shape) and never forms a cycle (the move walks the ancestor chain
 * before re-parenting: a node can not become its own descendant). Root
 * names are unique per menu (the V283 partial unique index).</p>
 */
@Service
@Transactional
public class CmsNavigationAdminService implements CmsNavigationPort {

    private static final Logger log = LoggerFactory.getLogger(CmsNavigationAdminService.class);
    private static final Pattern SLUG_SHAPE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private final CmsMenuRepository menuRepository;
    private final CmsMenuItemRepository itemRepository;
    private final CmsMenuItemTranslationRepository itemTranslationRepository;
    private final CmsNavigationSlotRepository slotRepository;

    public CmsNavigationAdminService(CmsMenuRepository menuRepository,
                                     CmsMenuItemRepository itemRepository,
                                     CmsMenuItemTranslationRepository itemTranslationRepository,
                                     CmsNavigationSlotRepository slotRepository) {
        this.menuRepository = menuRepository;
        this.itemRepository = itemRepository;
        this.itemTranslationRepository = itemTranslationRepository;
        this.slotRepository = slotRepository;
    }

    // ---------------------------------------------------------------------
    // Navigation slots
    // ---------------------------------------------------------------------

    @Override
    @Observed(name = "cms.navigation.assign")
    public void assignNavigation(Authentication caller, String navigationType, UUID menuId) {
        requireAdmin(caller);
        String slot = normalizeSlot(navigationType);
        if (menuId != null && !menuRepository.existsById(menuId)) {
            throw new ResourceNotFoundException("No menu " + menuId);
        }
        CmsNavigationSlot row = slotRepository.findBySlot(slot)
                .orElseGet(() -> {
                    CmsNavigationSlot fresh = CmsNavigationSlot.create(slot, null);
                    slotRepository.save(fresh);
                    return fresh;
                });
        row.assign(menuId);
        log.info("Navigation slot assigned: slot={}, menuId={}, actor={}", slot, menuId,
                caller.getName());
    }

    // ---------------------------------------------------------------------
    // Menus
    // ---------------------------------------------------------------------

    @Override
    @Observed(name = "cms.menu.create")
    public MenuView createMenu(Authentication caller, String name, String slug) {
        requireAdmin(caller);
        String trimmed = requireName(name);
        String resolvedSlug = requireFreeSlug(slug == null || slug.isBlank()
                ? slugify(trimmed) : slug.trim());
        CmsMenu row = menuRepository.save(CmsMenu.create(trimmed, resolvedSlug));
        log.info("CMS menu created: id={}, slug={}, actor={}", row.getId(), resolvedSlug,
                caller.getName());
        return toView(row);
    }

    @Override
    @Observed(name = "cms.menu.update")
    public MenuView updateMenu(Authentication caller, UUID id, String name, String slug) {
        requireAdmin(caller);
        CmsMenu row = requireMenu(id);
        if (slug != null && !slug.isBlank()) {
            String resolved = requireSlugShape(slug.trim());
            if (!resolved.equals(row.getSlug()) && menuRepository.existsBySlug(resolved)) {
                throw new ConflictException("The slug already exists: " + resolved);
            }
        }
        row.apply(name, slug);
        log.info("CMS menu updated: id={}, actor={}", id, caller.getName());
        return toView(row);
    }

    @Override
    @Observed(name = "cms.menu.delete")
    public MenuView deleteMenu(Authentication caller, UUID id) {
        requireAdmin(caller);
        CmsMenu row = requireMenu(id);
        itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(id).forEach(item -> {
            itemTranslationRepository.findAllByMenuItemId(item.getId())
                    .forEach(itemTranslationRepository::delete);
            itemRepository.delete(item);
        });
        slotRepository.findBySlot("MAIN").filter(s -> id.equals(s.getMenuId()))
                .ifPresent(s -> s.assign(null));
        slotRepository.findBySlot("SECONDARY").filter(s -> id.equals(s.getMenuId()))
                .ifPresent(s -> s.assign(null));
        menuRepository.delete(row);
        log.info("CMS menu deleted: id={}, actor={}", id, caller.getName());
        return toView(row);
    }

    @Override
    @Observed(name = "cms.menu.bulk_delete")
    public int bulkDeleteMenus(Authentication caller, List<UUID> ids) {
        requireAdmin(caller);
        int count = 0;
        for (UUID id : ids) {
            if (menuRepository.existsById(id)) {
                deleteMenu(caller, id);
                count++;
            }
        }
        log.info("CMS menus bulk-deleted: count={}, actor={}", count, caller.getName());
        return count;
    }

    // ---------------------------------------------------------------------
    // Menu items
    // ---------------------------------------------------------------------

    @Override
    @Observed(name = "cms.menu_item.create")
    public MenuItemView createMenuItem(Authentication caller, UUID menuId, UUID parentId,
                                       String name, String url, UUID categoryId, UUID pageId) {
        requireAdmin(caller);
        requireMenu(menuId);
        String trimmed = requireName(name);
        if (parentId == null && itemRepository.findByMenuIdAndNameAndParentIdIsNull(
                menuId, trimmed).isPresent()) {
            throw new ConflictException(
                    "A root menu item with this name already exists in the menu: " + trimmed);
        }
        validateTarget(url, categoryId, pageId);
        if (parentId != null) {
            CmsMenuItem parent = requireItem(parentId);
            if (!parent.getMenuId().equals(menuId)) {
                throw new IllegalArgumentException("The parent item belongs to another menu");
            }
        }
        List<CmsMenuItem> siblings = parentId == null
                ? itemRepository.findAllByMenuIdAndParentIdIsNullOrderByPositionAscIdAsc(menuId)
                : itemRepository.findAllByMenuIdAndParentIdOrderByPositionAscIdAsc(menuId, parentId);
        CmsMenuItem row = CmsMenuItem.create(menuId, parentId, trimmed);
        row.apply(null, url, categoryId, pageId);
        row.moveTo(siblings.size());
        itemRepository.save(row);
        log.info("CMS menu item created: id={}, menuId={}, actor={}", row.getId(), menuId,
                caller.getName());
        return toView(row);
    }

    @Override
    @Observed(name = "cms.menu_item.update")
    public MenuItemView updateMenuItem(Authentication caller, UUID id, String name, String url,
                                       UUID categoryId, UUID pageId) {
        requireAdmin(caller);
        CmsMenuItem row = requireItem(id);
        validateTarget(url, categoryId, pageId);
        row.apply(name, url, categoryId, pageId);
        log.info("CMS menu item updated: id={}, actor={}", id, caller.getName());
        return toView(row);
    }

    @Override
    @Observed(name = "cms.menu_item.delete")
    public MenuItemView deleteMenuItem(Authentication caller, UUID id) {
        requireAdmin(caller);
        CmsMenuItem row = requireItem(id);
        // The subtree re-parents to the deleted node's parent (the tree
        // never orphans), then positions resequence.
        // The subtree re-parents to the deleted node's parent (the tree
        // never orphans), then positions resequence.
        for (CmsMenuItem child : itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(row.getMenuId())) {
            if (id.equals(child.getParentId())) {
                child.reParent(row.getParentId());
            }
        }
        itemRepository.delete(row);
        resequence(row.getMenuId());
        log.info("CMS menu item deleted: id={}, actor={}", id, caller.getName());
        return toView(row);
    }

    @Override
    @Observed(name = "cms.menu_item.bulk_delete")
    public int bulkDeleteMenuItems(Authentication caller, List<UUID> ids) {
        requireAdmin(caller);
        int count = 0;
        for (UUID id : ids) {
            if (itemRepository.existsById(id)) {
                deleteMenuItem(caller, id);
                count++;
            }
        }
        log.info("CMS menu items bulk-deleted: count={}, actor={}", count, caller.getName());
        return count;
    }

    @Override
    @Observed(name = "cms.menu_item.translate")
    public MenuItemView translateMenuItem(Authentication caller, UUID id, String languageCode,
                                          String name) {
        requireAdmin(caller);
        CmsMenuItem item = requireItem(id);
        String language = requireLanguage(languageCode);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A translated name is required");
        }
        CmsMenuItemTranslation translation = itemTranslationRepository
                .findByMenuItemIdAndLanguageCode(id, language)
                .orElseGet(() -> {
                    CmsMenuItemTranslation fresh = CmsMenuItemTranslation.create(id, language,
                            name.trim());
                    itemTranslationRepository.save(fresh);
                    return fresh;
                });
        translation.apply(name);
        log.info("CMS menu item translated: id={}, language={}, actor={}", id, language,
                caller.getName());
        return toView(item);
    }

    @Override
    @Observed(name = "cms.menu_item.move")
    public List<MenuItemView> moveMenuItems(Authentication caller, UUID menuId,
                                            List<UUID> itemIds, List<UUID> parentIds,
                                            List<Integer> sortOrders) {
        requireAdmin(caller);
        requireMenu(menuId);
        List<MenuItemView> moved = new ArrayList<>(itemIds.size());
        for (int i = 0; i < itemIds.size(); i++) {
            CmsMenuItem item = requireItem(itemIds.get(i));
            if (!item.getMenuId().equals(menuId)) {
                throw new IllegalArgumentException("The item belongs to another menu");
            }
            UUID newParent = parentIds.get(i);
            if (newParent != null) {
                if (newParent.equals(item.getId())) {
                    throw new IllegalArgumentException("A menu item cannot be its own parent");
                }
                CmsMenuItem parent = requireItem(newParent);
                if (!parent.getMenuId().equals(menuId)) {
                    throw new IllegalArgumentException("The parent item belongs to another menu");
                }
                if (isDescendant(parent, item.getId())) {
                    throw new ConflictException(
                            "The move would create a cycle in the menu tree");
                }
            }
            item.reParent(newParent);
            moved.add(toView(item));
        }
        // The relative sort orders apply AFTER the re-parenting: each item's
        // position shifts by its delta, then every sibling group resequences dense.
        for (int i = 0; i < itemIds.size(); i++) {
            CmsMenuItem item = requireItem(itemIds.get(i));
            int delta = sortOrders.get(i) == null ? 0 : sortOrders.get(i);
            item.moveTo(Math.max(0, item.getPosition() + delta));
        }
        resequence(menuId);
        log.info("CMS menu items moved: menuId={}, moves={}, actor={}", menuId, itemIds.size(),
                caller.getName());
        return moved;
    }

    // ---------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<MenuView> findMenuById(Authentication caller, UUID id) {
        requireAdmin(caller);
        return menuRepository.findById(id).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MenuView> findMenuBySlug(Authentication caller, String slug) {
        requireAdmin(caller);
        return menuRepository.findBySlug(slug).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MenuView> findMenuByName(Authentication caller, String name) {
        requireAdmin(caller);
        return menuRepository.findByName(name).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public MenuWindow scrollMenus(Authentication caller, String sortField, boolean ascending,
                                  KeysetScrollPosition position, Limit limit) {
        requireAdmin(caller);
        Window<CmsMenu> window = switch (sortField == null ? "NAME" : sortField) {
            case "SLUG" -> ascending
                    ? menuRepository.findAllByOrderBySlugAscIdAsc(position, limit)
                    : menuRepository.findAllByOrderBySlugDescIdDesc(position, limit);
            default -> ascending
                    ? menuRepository.findAllByOrderByNameAscIdAsc(position, limit)
                    : menuRepository.findAllByOrderByNameDescIdDesc(position, limit);
        };
        return new MenuWindow(window.map(this::toView), menuRepository.count());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MenuItemView> findMenuItemById(Authentication caller, UUID id) {
        requireAdmin(caller);
        return itemRepository.findById(id).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuItemView> listMenuItems(Authentication caller, UUID menuId) {
        requireAdmin(caller);
        requireMenu(menuId);
        return itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId)
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MenuItemWindow scrollMenuItems(Authentication caller, String sortField,
                                          boolean ascending, KeysetScrollPosition position,
                                          Limit limit) {
        requireAdmin(caller);
        // The item collection's owning sorts: NAME (the item's own column)
        // and SLUG (rendered from the name composition the same way).
        Window<CmsMenuItem> window = ascending
                ? itemsWindow(position, limit, true)
                : itemsWindow(position, limit, false);
        return new MenuItemWindow(window.map(this::toView), itemRepository.count());
    }

    private Window<CmsMenuItem> itemsWindow(KeysetScrollPosition position, Limit limit,
                                            boolean ascending) {
        // The item scroll rides the whole-collection name sorts; the tree
        // composition happens per-menu at the boundary.
        return ascending
                ? org.springframework.data.domain.Window.from(
                        itemRepository.findAll(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.ASC, "name", "id"))
                                .stream().limit(limit.max()).toList(),
                        org.springframework.data.domain.ScrollPosition::offset)
                : org.springframework.data.domain.Window.from(
                        itemRepository.findAll(org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "name", "id"))
                                .stream().limit(limit.max()).toList(),
                        org.springframework.data.domain.ScrollPosition::offset);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** Walks the ancestor chain — true when candidate is below the node. */
    private boolean isDescendant(CmsMenuItem candidate, UUID ancestorId) {
        UUID parentId = candidate.getParentId();
        while (parentId != null) {
            if (parentId.equals(ancestorId)) {
                return true;
            }
            final UUID current = parentId;
            parentId = itemRepository.findById(current).map(CmsMenuItem::getParentId).orElse(null);
        }
        return false;
    }

    private void resequence(UUID menuId) {
        Map<UUID, List<CmsMenuItem>> byParent = new HashMap<>();
        for (CmsMenuItem item : itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId)) {
            byParent.computeIfAbsent(item.getParentId(), key -> new ArrayList<>()).add(item);
        }
        for (List<CmsMenuItem> group : byParent.values()) {
            for (int i = 0; i < group.size(); i++) {
                group.get(i).moveTo(i);
            }
        }
    }

    private MenuView toView(CmsMenu row) {
        return new MenuView(row.getId(), row.getName(), row.getSlug());
    }

    private MenuItemView toView(CmsMenuItem row) {
        Map<String, String> translations = new HashMap<>();
        for (var translation : itemTranslationRepository.findAllByMenuItemId(row.getId())) {
            translations.put(translation.getLanguageCode(), translation.getName());
        }
        return new MenuItemView(row.getId(), row.getMenuId(), row.getParentId(), row.getName(),
                row.getUrl(), row.getCategoryId(), row.getPageId(), row.getPosition(),
                translations);
    }

    private CmsMenu requireMenu(UUID id) {
        return menuRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No menu " + id));
    }

    private CmsMenuItem requireItem(UUID id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No menu item " + id));
    }

    private static void validateTarget(String url, UUID categoryId, UUID pageId) {
        int targets = (url != null && !url.isBlank() ? 1 : 0)
                + (categoryId != null ? 1 : 0) + (pageId != null ? 1 : 0);
        if (targets > 1) {
            throw new IllegalArgumentException(
                    "A menu item targets at most one of a URL, a category or a page");
        }
    }

    private static String normalizeSlot(String navigationType) {
        if (navigationType == null) {
            throw new IllegalArgumentException("A navigation type (MAIN or SECONDARY) is required");
        }
        String normalized = navigationType.trim().toUpperCase(Locale.ROOT);
        if (!"MAIN".equals(normalized) && !"SECONDARY".equals(normalized)) {
            throw new IllegalArgumentException("Unknown navigation type: " + navigationType);
        }
        return normalized;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 255) {
            throw new IllegalArgumentException("The name exceeds 255 characters");
        }
        return trimmed;
    }

    private String requireFreeSlug(String slug) {
        requireSlugShape(slug);
        if (menuRepository.existsBySlug(slug)) {
            throw new ConflictException("The slug already exists: " + slug);
        }
        return slug;
    }

    private static String requireSlugShape(String slug) {
        if (slug == null || !SLUG_SHAPE.matcher(slug).matches()) {
            throw new IllegalArgumentException("The slug must be lowercase kebab-case: " + slug);
        }
        return slug;
    }

    private static String requireLanguage(String languageCode) {
        if (languageCode == null || !languageCode.matches("^[a-z]{2}(-[A-Za-z]{2,4})?$")) {
            throw new IllegalArgumentException("A language code is required: " + languageCode);
        }
        return languageCode;
    }

    private static String slugify(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static void requireAdmin(Authentication caller) {
        boolean admin = caller.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException(
                    "CMS navigation administration requires the ADMIN capability");
        }
    }
}
