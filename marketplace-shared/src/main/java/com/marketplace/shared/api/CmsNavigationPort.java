package com.marketplace.shared.api;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-CMS-001 ownership decision): the CMS navigation port — the menus,
 * menu items (tree with cycle-guarded moves) and the navigation slot
 * assignment behind the Saleor Menu-domain roots. The same shared-api
 * port idiom and seam discipline as {@link CmsPagePort}.
 */
public interface CmsNavigationPort {

    /** The menu projection. */
    record MenuView(UUID id, String name, String slug) {
    }

    /** The menu item projection (translations keyed by language code). */
    record MenuItemView(UUID id, UUID menuId, UUID parentId, String name, String url,
                        UUID categoryId, UUID pageId, int position,
                        Map<String, String> translations) {
    }

    /** One page of the collection (the forward-only keyset contract). */
    record MenuWindow(Window<MenuView> window, long total) {
    }

    /** One menu-item page of the collection. */
    record MenuItemWindow(Window<MenuItemView> window, long total) {
    }

    void assignNavigation(Authentication caller, String navigationType, UUID menuId);

    MenuView createMenu(Authentication caller, String name, String slug);

    MenuView updateMenu(Authentication caller, UUID id, String name, String slug);

    MenuView deleteMenu(Authentication caller, UUID id);

    int bulkDeleteMenus(Authentication caller, List<UUID> ids);

    MenuItemView createMenuItem(Authentication caller, UUID menuId, UUID parentId, String name,
                                String url, UUID categoryId, UUID pageId);

    MenuItemView updateMenuItem(Authentication caller, UUID id, String name, String url,
                                UUID categoryId, UUID pageId);

    MenuItemView deleteMenuItem(Authentication caller, UUID id);

    int bulkDeleteMenuItems(Authentication caller, List<UUID> ids);

    MenuItemView translateMenuItem(Authentication caller, UUID id, String languageCode, String name);

    /** The cycle-guarded move batch (Saleor's menuItemMove semantics). */
    List<MenuItemView> moveMenuItems(Authentication caller, UUID menuId,
                                     List<UUID> itemIds, List<UUID> parentIds, List<Integer> sortOrders);

    Optional<MenuView> findMenuById(Authentication caller, UUID id);

    Optional<MenuView> findMenuBySlug(Authentication caller, String slug);

    Optional<MenuView> findMenuByName(Authentication caller, String name);

    MenuWindow scrollMenus(Authentication caller, String sortField, boolean ascending,
                           KeysetScrollPosition position, Limit limit);

    Optional<MenuItemView> findMenuItemById(Authentication caller, UUID id);

    /** The menu's whole item set (the boundary composes the tree + levels). */
    List<MenuItemView> listMenuItems(Authentication caller, UUID menuId);

    MenuItemWindow scrollMenuItems(Authentication caller, String sortField, boolean ascending,
                                   KeysetScrollPosition position, Limit limit);
}
