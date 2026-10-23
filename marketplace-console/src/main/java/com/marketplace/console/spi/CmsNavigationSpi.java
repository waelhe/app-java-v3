package com.marketplace.console.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 / A0 (the Saleor 458-root campaign — the post-merge integration
 * review of PR #696): the CMS navigation SPI — the menus, menu items
 * (tree with cycle-guarded moves) and the navigation slot assignment
 * behind the Saleor Menu-domain roots.
 *
 * <p><b>Why this interface moved out of {@code shared/api}:</b> it was
 * born there as {@code CmsNavigationPort} riding the shared-api port
 * idiom, but its contract speaks Spring — exactly what the house rule
 * {@code sharedPortsAreFrameworkNeutral} forbids in the shared-contract
 * module. The recorded migration path in the agent-1 handoff ("a
 * console-owned named SPI when the allowlist entry lands") is executed
 * here: the seam now lives where the owning domain owns it — the same
 * {@code IdentitySpi}/{@code catalog-spi} precedent every journey seam
 * follows (see {@link CmsPageSpi} for the full record).</p>
 */
@org.springframework.modulith.NamedInterface("console-spi")
public interface CmsNavigationSpi {

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
