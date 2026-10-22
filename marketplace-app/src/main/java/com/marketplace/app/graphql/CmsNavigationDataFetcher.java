package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.AssignNavigation;
import com.marketplace.app.graphql.generated.types.LanguageCodeEnum;
import com.marketplace.app.graphql.generated.types.Menu;
import com.marketplace.app.graphql.generated.types.MenuCountableConnection;
import com.marketplace.app.graphql.generated.types.MenuCreate;
import com.marketplace.app.graphql.generated.types.MenuCreateInput;
import com.marketplace.app.graphql.generated.types.MenuDelete;
import com.marketplace.app.graphql.generated.types.MenuInput;
import com.marketplace.app.graphql.generated.types.MenuItem;
import com.marketplace.app.graphql.generated.types.MenuItemCountableConnection;
import com.marketplace.app.graphql.generated.types.MenuItemCreate;
import com.marketplace.app.graphql.generated.types.MenuItemCreateInput;
import com.marketplace.app.graphql.generated.types.MenuItemDelete;
import com.marketplace.app.graphql.generated.types.MenuItemInput;
import com.marketplace.app.graphql.generated.types.MenuItemMove;
import com.marketplace.app.graphql.generated.types.MenuItemMoveInput;
import com.marketplace.app.graphql.generated.types.MenuItemTranslate;
import com.marketplace.app.graphql.generated.types.MenuItemUpdate;
import com.marketplace.app.graphql.generated.types.MenuSortingInput;
import com.marketplace.app.graphql.generated.types.MenuUpdate;
import com.marketplace.app.graphql.generated.types.NameTranslationInput;
import com.marketplace.app.graphql.generated.types.NavigationType;
import com.marketplace.shared.api.CmsNavigationPort;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-CMS-001 ownership decision): the GraphQL adapter for the navigation
 * surfaces — {@code assignNavigation}, the {@code menu*} and
 * {@code menuItem*} mutations, and the {@code menu(s)}/
 * {@code menuItem(s)} read roots, delegating through the declared
 * {@link CmsNavigationPort} seam. The tree composition (children lists,
 * levels) is computed here from the owner's flat item set; the cycle
 * guard and the cross-menu invariants live in the owning use case.
 */
@DgsComponent
public class CmsNavigationDataFetcher {

    static final String MENU_TAG = "Menu";
    static final String MENU_ITEM_TAG = "MenuItem";

    private final CmsNavigationPort navigationPort;
    private final SaleorGlobalIdCodec globalIdCodec;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final GraphQlProperties properties;

    public CmsNavigationDataFetcher(CmsNavigationPort navigationPort,
                                    SaleorGlobalIdCodec globalIdCodec,
                                    EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                    GraphQlProperties properties) {
        this.navigationPort = navigationPort;
        this.globalIdCodec = globalIdCodec;
        this.cursorStrategy = cursorStrategy;
        this.properties = properties;
    }

    // ---------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------

    @DgsMutation(field = DgsConstants.MUTATION.AssignNavigation)
    @RateLimiter(name = "graphqlCmsMutation")
    public AssignNavigation assignNavigation(@InputArgument String menu,
                                             @InputArgument NavigationType navigationType) {
        if (navigationType == null) {
            throw new IllegalArgumentException("A navigation type (MAIN or SECONDARY) is required");
        }
        navigationPort.assignNavigation(currentAuthentication(), navigationType.name(),
                menu == null || menu.isBlank() ? null : decodeId(menu, MENU_TAG, "A menu ID"));
        return AssignNavigation.newBuilder()
                .errors(List.of())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuCreate)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuCreate menuCreate(@InputArgument MenuCreateInput input) {
        if (input == null || input.getName() == null || input.getName().isBlank()) {
            throw new IllegalArgumentException("A menu name is required");
        }
        return MenuCreate.newBuilder()
                .menu(toGraphQlMenu(navigationPort.createMenu(currentAuthentication(),
                        input.getName(), input.getSlug())))
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuUpdate)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuUpdate menuUpdate(@InputArgument String id,
                                 @InputArgument MenuInput input) {
        if (input == null) {
            throw new IllegalArgumentException("A menu input is required");
        }
        return MenuUpdate.newBuilder()
                .menu(toGraphQlMenu(navigationPort.updateMenu(currentAuthentication(),
                        decodeId(id, MENU_TAG, "A menu ID is required"), input.getName(),
                        input.getSlug())))
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuDelete)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuDelete menuDelete(@InputArgument String id) {
        return MenuDelete.newBuilder()
                .menu(toGraphQlMenu(navigationPort.deleteMenu(currentAuthentication(),
                        decodeId(id, MENU_TAG, "A menu ID is required"))))
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuBulkDelete)
    @RateLimiter(name = "graphqlCmsMutation")
    public com.marketplace.app.graphql.generated.types.MenuBulkDelete menuBulkDelete(
            @InputArgument List<String> ids) {
        int count = navigationPort.bulkDeleteMenus(currentAuthentication(),
                decodeIds(ids, MENU_TAG, "A list of menu IDs is required"));
        return com.marketplace.app.graphql.generated.types.MenuBulkDelete.newBuilder()
                .count(count)
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuItemCreate)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuItemCreate menuItemCreate(@InputArgument MenuItemCreateInput input,
                                         @InputArgument String menu,
                                         @InputArgument String parent) {
        if (input == null || input.getName() == null || input.getName().isBlank()) {
            throw new IllegalArgumentException("A menu item name is required");
        }
        return MenuItemCreate.newBuilder()
                .menuItem(toGraphQlMenuItem(navigationPort.createMenuItem(currentAuthentication(),
                        decodeId(menu, MENU_TAG, "A menu ID is required"),
                        parent == null || parent.isBlank() ? null
                                : decodeId(parent, MENU_ITEM_TAG, "A parent item ID"),
                        input.getName(), input.getUrl(),
                        decodeNullableId(input.getCategory(), "Category"),
                        decodeNullableId(input.getPage(), "Page"))))
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuItemUpdate)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuItemUpdate menuItemUpdate(@InputArgument String id,
                                         @InputArgument MenuItemInput input) {
        if (input == null) {
            throw new IllegalArgumentException("A menu item input is required");
        }
        return MenuItemUpdate.newBuilder()
                .menuItem(toGraphQlMenuItem(navigationPort.updateMenuItem(currentAuthentication(),
                        decodeId(id, MENU_ITEM_TAG, "A menu item ID is required"),
                        input.getName(), input.getUrl(),
                        decodeNullableId(input.getCategory(), "Category"),
                        decodeNullableId(input.getPage(), "Page"))))
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuItemDelete)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuItemDelete menuItemDelete(@InputArgument String id) {
        return MenuItemDelete.newBuilder()
                .menuItem(toGraphQlMenuItem(navigationPort.deleteMenuItem(currentAuthentication(),
                        decodeId(id, MENU_ITEM_TAG, "A menu item ID is required"))))
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuItemBulkDelete)
    @RateLimiter(name = "graphqlCmsMutation")
    public com.marketplace.app.graphql.generated.types.MenuItemBulkDelete menuItemBulkDelete(
            @InputArgument List<String> ids) {
        int count = navigationPort.bulkDeleteMenuItems(currentAuthentication(),
                decodeIds(ids, MENU_ITEM_TAG, "A list of menu item IDs is required"));
        return com.marketplace.app.graphql.generated.types.MenuItemBulkDelete.newBuilder()
                .count(count)
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuItemTranslate)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuItemTranslate menuItemTranslate(@InputArgument String id,
                                               @InputArgument NameTranslationInput input,
                                               @InputArgument LanguageCodeEnum languageCode) {
        if (input == null || input.getName() == null || input.getName().isBlank()) {
            throw new IllegalArgumentException("A translated name is required");
        }
        if (languageCode == null) {
            throw new IllegalArgumentException("A language code is required");
        }
        return MenuItemTranslate.newBuilder()
                .menuItem(toGraphQlMenuItem(navigationPort.translateMenuItem(
                        currentAuthentication(),
                        decodeId(id, MENU_ITEM_TAG, "A menu item ID is required"),
                        languageCode.name(), input.getName())))
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuItemMove)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuItemMove menuItemMove(@InputArgument String menu,
                                     @InputArgument List<MenuItemMoveInput> moves) {
        if (moves == null || moves.isEmpty()) {
            throw new IllegalArgumentException("A list of move operations is required");
        }
        UUID menuId = decodeId(menu, MENU_TAG, "A menu ID is required");
        List<UUID> itemIds = new ArrayList<>(moves.size());
        List<UUID> parentIds = new ArrayList<>(moves.size());
        List<Integer> sortOrders = new ArrayList<>(moves.size());
        for (MenuItemMoveInput move : moves) {
            itemIds.add(decodeId(move.getItemId(), MENU_ITEM_TAG, "A menu item ID is required"));
            parentIds.add(move.getParentId() == null || move.getParentId().isBlank() ? null
                    : decodeId(move.getParentId(), MENU_ITEM_TAG, "A parent item ID"));
            sortOrders.add(move.getSortOrder());
        }
        navigationPort.moveMenuItems(currentAuthentication(), menuId, itemIds, parentIds,
                sortOrders);
        return MenuItemMove.newBuilder()
                .menu(toGraphQlMenu(new CmsNavigationPort.MenuView(menuId, null, null)))
                .errors(mappersEmpty())
                .build();
    }

    // ---------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------

    @DgsQuery(field = DgsConstants.QUERY.Menu)
    @RateLimiter(name = "graphqlCmsQuery")
    public Menu menu(@InputArgument String channel,
                     @InputArgument String id,
                     @InputArgument String name,
                     @InputArgument String slug) {
        rejectChannel(channel);
        Authentication caller = currentAuthentication();
        if (id != null && !id.isBlank()) {
            return navigationPort.findMenuById(caller, decodeId(id, MENU_TAG, "A menu ID"))
                    .map(view -> toMenuWithItems(caller, view)).orElse(null);
        }
        if (slug != null && !slug.isBlank()) {
            return navigationPort.findMenuBySlug(caller, slug)
                    .map(view -> toMenuWithItems(caller, view)).orElse(null);
        }
        if (name != null && !name.isBlank()) {
            return navigationPort.findMenuByName(caller, name)
                    .map(view -> toMenuWithItems(caller, view)).orElse(null);
        }
        throw new IllegalArgumentException("A menu ID, slug or name is required");
    }

    @DgsQuery(field = DgsConstants.QUERY.Menus)
    @RateLimiter(name = "graphqlCmsQuery")
    public MenuCountableConnection menus(@InputArgument Integer first,
                                         @InputArgument String after,
                                         @InputArgument String before,
                                         @InputArgument Integer last,
                                         @InputArgument String channel,
                                         @InputArgument Object filter,
                                         @InputArgument MenuSortingInput sortBy) {
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "menus supports forward-only pagination (first/after) on this platform");
        }
        if (filter != null) {
            throw new IllegalArgumentException(
                    "menus filter is not implemented on this platform yet");
        }
        rejectChannel(channel);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return MenuCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(pageInfoOf(false))
                    .build();
        }
        String field = sortBy == null ? "NAME" : sortBy.getField() == null
                ? "NAME" : sortBy.getField().name();
        boolean ascending = sortBy == null || sortBy.getDirection() == null
                || com.marketplace.app.graphql.generated.types.OrderDirection.ASC
                        .equals(sortBy.getDirection());
        CmsNavigationPort.MenuWindow window = navigationPort.scrollMenus(currentAuthentication(),
                field, ascending, keysetPosition(after, field),
                Limit.of(Math.min(requested, properties.pagination().maxPageSize())));
        List<com.marketplace.app.graphql.generated.types.MenuCountableEdge> edges =
                new ArrayList<>(window.window().getContent().size());
        for (int i = 0; i < window.window().getContent().size(); i++) {
            edges.add(com.marketplace.app.graphql.generated.types.MenuCountableEdge.newBuilder()
                    .node(toGraphQlMenu(window.window().getContent().get(i)))
                    .cursor(cursorStrategy.toCursor(window.window().positionAt(i)))
                    .build());
        }
        return MenuCountableConnection.newBuilder()
                .edges(edges)
                .pageInfo(pageInfoOf(window.window().hasNext()))
                .totalCount((int) Math.min(window.total(), Integer.MAX_VALUE))
                .build();
    }

    @DgsQuery(field = DgsConstants.QUERY.MenuItem)
    @RateLimiter(name = "graphqlCmsQuery")
    public MenuItem menuItem(@InputArgument String channel,
                             @InputArgument String id) {
        rejectChannel(channel);
        return navigationPort.findMenuItemById(currentAuthentication(),
                        decodeId(id, MENU_ITEM_TAG, "A menu item ID is required"))
                .map(this::toGraphQlMenuItem).orElse(null);
    }

    @DgsQuery(field = DgsConstants.QUERY.MenuItems)
    @RateLimiter(name = "graphqlCmsQuery")
    public MenuItemCountableConnection menuItems(@InputArgument Integer first,
                                                 @InputArgument String after,
                                                 @InputArgument String before,
                                                 @InputArgument Integer last,
                                                 @InputArgument String channel,
                                                 @InputArgument Object filter,
                                                 @InputArgument Object sortBy) {
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "menuItems supports forward-only pagination (first/after) on this platform");
        }
        if (filter != null || sortBy != null) {
            throw new IllegalArgumentException(
                    "menuItems filter/sortBy is not implemented on this platform yet");
        }
        rejectChannel(channel);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return MenuItemCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(pageInfoOf(false))
                    .build();
        }
        CmsNavigationPort.MenuItemWindow window = navigationPort.scrollMenuItems(
                currentAuthentication(), "NAME", true, keysetPosition(after, "NAME"),
                Limit.of(Math.min(requested, properties.pagination().maxPageSize())));
        List<com.marketplace.app.graphql.generated.types.MenuItemCountableEdge> edges =
                new ArrayList<>(window.window().getContent().size());
        for (int i = 0; i < window.window().getContent().size(); i++) {
            edges.add(com.marketplace.app.graphql.generated.types.MenuItemCountableEdge.newBuilder()
                    .node(toGraphQlMenuItem(window.window().getContent().get(i)))
                    .cursor(cursorStrategy.toCursor(window.window().positionAt(i)))
                    .build());
        }
        return MenuItemCountableConnection.newBuilder()
                .edges(edges)
                .pageInfo(pageInfoOf(window.window().hasNext()))
                .totalCount((int) Math.min(window.total(), Integer.MAX_VALUE))
                .build();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * The connections' forward keyset position (B4, the CodeRabbit
     * correctness round): the resolvers previously declared {@code after}
     * and always passed the INITIAL keyset position — every request
     * returned the first page, and a client following {@code endCursor}
     * looped forever. The cursor gate now decodes the emitted cursor
     * against the connection's own sort keys: the menus' NAME|SLUG
     * vocabulary and the items' NAME vocabulary, both (own-column, id)
     * keysets.
     */
    private org.springframework.data.domain.KeysetScrollPosition keysetPosition(
            String after, String sortField) {
        String property = "SLUG".equals(sortField) ? "slug" : "name";
        return KeysetCursorGate.forwardKeyset(cursorStrategy, after, property,
                KeysetCursorGate::textValue, "the connection with the same sortBy");
    }

    private Menu toMenuWithItems(Authentication caller, CmsNavigationPort.MenuView view) {
        List<CmsNavigationPort.MenuItemView> items = navigationPort.listMenuItems(caller,
                view.id());
        Map<UUID, List<CmsNavigationPort.MenuItemView>> byParent = new HashMap<>();
        for (CmsNavigationPort.MenuItemView item : items) {
            byParent.computeIfAbsent(item.parentId(), key -> new ArrayList<>()).add(item);
        }
        Map<UUID, Integer> levels = new HashMap<>();
        for (CmsNavigationPort.MenuItemView item : items) {
            levels.put(item.id(), levelOf(item, byParent));
        }
        List<MenuItem> roots = byParent.getOrDefault(null, List.of()).stream()
                .map(item -> toGraphQlMenuItem(item, byParent, levels))
                .toList();
        return Menu.newBuilder()
                .id(globalIdCodec.encode(MENU_TAG, view.id()))
                .name(view.name() == null ? "" : view.name())
                .slug(view.slug() == null ? "" : view.slug())
                .items(roots)
                .build();
    }

    private static int levelOf(CmsNavigationPort.MenuItemView item,
                               Map<UUID, List<CmsNavigationPort.MenuItemView>> byParent) {
        // The flat set is ordered by (parent, position); levels computed by
        // walking the parent chain — the owner's cycle guard guarantees it
        // terminates.
        int level = 0;
        UUID parentId = item.parentId();
        while (parentId != null && level < 64) {
            level++;
            UUID current = parentId;
            parentId = byParent.values().stream()
                    .flatMap(List::stream)
                    .filter(candidate -> candidate.id().equals(current))
                    .map(CmsNavigationPort.MenuItemView::parentId)
                    .findFirst().orElse(null);
        }
        return level;
    }

    private MenuItem toGraphQlMenuItem(CmsNavigationPort.MenuItemView view) {
        return toGraphQlMenuItem(view, Map.of(), Map.of());
    }

    private MenuItem toGraphQlMenuItem(CmsNavigationPort.MenuItemView view,
                                       Map<UUID, List<CmsNavigationPort.MenuItemView>> byParent,
                                       Map<UUID, Integer> levels) {
        List<MenuItem> children = byParent.getOrDefault(view.id(), List.of()).stream()
                .map(child -> toGraphQlMenuItem(child, byParent, levels))
                .toList();
        return MenuItem.newBuilder()
                .id(globalIdCodec.encode(MENU_ITEM_TAG, view.id()))
                .menu(Menu.newBuilder()
                        .id(globalIdCodec.encode(MENU_TAG, view.menuId()))
                        .name("").slug("")
                        .build())
                ._parent(view.parentId() == null ? null : MenuItem.newBuilder()
                        .id(globalIdCodec.encode(MENU_ITEM_TAG, view.parentId()))
                        .menu(Menu.newBuilder().id(globalIdCodec.encode(MENU_TAG, view.menuId()))
                                .name("").slug("").build())
                        .name("")
                        .level(levels.getOrDefault(view.parentId(), 0))
                        .build())
                .name(view.name())
                .url(view.url())
                .level(levels.getOrDefault(view.id(), 0))
                .children(children.isEmpty() ? null : children)
                .build();
    }

    private Menu toGraphQlMenu(CmsNavigationPort.MenuView view) {
        return Menu.newBuilder()
                .id(globalIdCodec.encode(MENU_TAG, view.id()))
                .name(view.name() == null ? "" : view.name())
                .slug(view.slug() == null ? "" : view.slug())
                .items(List.of())
                .build();
    }

    private static com.marketplace.app.graphql.generated.types.PageInfo pageInfoOf(boolean hasNext) {
        return com.marketplace.app.graphql.generated.types.PageInfo.newBuilder()
                .hasNextPage(hasNext)
                .hasPreviousPage(false)
                .build();
    }

    private static List<com.marketplace.app.graphql.generated.types.MenuError> mappersEmpty() {
        return List.of();
    }

    private static void rejectChannel(String channel) {
        if (channel != null && !channel.isBlank()) {
            throw new IllegalArgumentException(
                    "The channel argument is not implemented on this platform yet "
                            + "(the platform is a single-channel store)");
        }
    }

    private UUID decodeId(String id, String tag, String message) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return globalIdCodec.decode(id, tag);
    }

    private UUID decodeNullableId(String id, String tag) {
        return id == null || id.isBlank() ? null : globalIdCodec.decode(id, tag);
    }

    private List<UUID> decodeIds(List<String> ids, String tag, String message) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return ids.stream().map(id -> decodeId(id, tag, message)).toList();
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.authentication
                    .AuthenticationCredentialsNotFoundException(
                    "Authentication is required for navigation administration");
        }
        return authentication;
    }
}
