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
import com.marketplace.app.graphql.generated.types.MenuItemTranslation;
import com.marketplace.app.graphql.generated.types.MenuItemUpdate;
import com.marketplace.app.graphql.generated.types.MenuSortingInput;
import com.marketplace.app.graphql.generated.types.MenuUpdate;
import com.marketplace.app.graphql.generated.types.NameTranslationInput;
import com.marketplace.app.graphql.generated.types.NavigationType;
import com.marketplace.console.spi.CmsNavigationSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-CMS-001 ownership decision): the GraphQL adapter for the navigation
 * surfaces — {@code assignNavigation}, the {@code menu*} and
 * {@code menuItem*} mutations, and the {@code menu(s)}/
 * {@code menuItem(s)} read roots, delegating through the declared
 * {@link CmsNavigationSpi} seam. The tree composition (children lists,
 * levels) is computed here from the owner's flat item set; the cycle
 * guard and the cross-menu invariants live in the owning use case. The
 * seam moved from the shared-api {@code CmsNavigationPort} to the
 * console-owned named SPI in the post-merge integration round (the
 * {@code sharedPortsAreFrameworkNeutral} repair — the recorded agent-1
 * handoff migration path, executed).
 */
@DgsComponent
public class CmsNavigationDataFetcher {

    static final String MENU_TAG = "Menu";
    static final String MENU_ITEM_TAG = "MenuItem";

    /**
     * The scrolls' cursor-fingerprint identities (B18/CAT-06 — the 2026-11-03
     * adversarial review, review-pr-808): the menus and menuItems
     * connections carry DISTINCT stamps — they shared one gate identity
     * ("the connection with the same sortBy") before, so a menus cursor
     * passed the menuItems gate verbatim.
     */
    private static final String MENUS_SCROLL_ID = "menus with the same sortBy";
    private static final String MENU_ITEMS_SCROLL_ID = "menuItems with the same sortBy";

    private final CmsNavigationSpi navigationPort;
    private final SaleorGlobalIdCodec globalIdCodec;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final GraphQlProperties properties;
    private final com.marketplace.catalog.spi.CategoryReadSpi categoryReadSpi;
    private final CategoryGraphQlMapper categoryMapper;
    private final com.marketplace.console.spi.CmsPageSpi cmsPageSpi;
    private final CmsPageGraphQlMapper pageMapper;

    public CmsNavigationDataFetcher(CmsNavigationSpi navigationPort,
                                    SaleorGlobalIdCodec globalIdCodec,
                                    EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                    GraphQlProperties properties,
                                    com.marketplace.catalog.spi.CategoryReadSpi categoryReadSpi,
                                    CategoryGraphQlMapper categoryMapper,
                                    com.marketplace.console.spi.CmsPageSpi cmsPageSpi,
                                    CmsPageGraphQlMapper pageMapper) {
        this.navigationPort = navigationPort;
        this.globalIdCodec = globalIdCodec;
        this.cursorStrategy = cursorStrategy;
        this.properties = properties;
        this.categoryReadSpi = categoryReadSpi;
        this.categoryMapper = categoryMapper;
        this.cmsPageSpi = cmsPageSpi;
        this.pageMapper = pageMapper;
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
        java.util.UUID menuId = menu == null || menu.isBlank()
                ? null : decodeId(menu, MENU_TAG, "A menu ID");
        navigationPort.assignNavigation(currentAuthentication(), navigationType.name(), menuId);
        // The 2026-11-06 #806 round-5 review (Minor · Data Integrity &
        // Integration): the pinned Saleor contract returns the ASSIGNED
        // menu on success — re-read after the assignment (the same
        // items-carrying render the menu mutations use), and keep menu
        // null for the detach call (menu: null) exactly as the contract
        // states.
        Menu assigned = menuId == null ? null
                : navigationPort.findMenuById(currentAuthentication(), menuId)
                        .map(view -> toMenuWithItems(currentAuthentication(), view))
                        .orElse(null);
        return AssignNavigation.newBuilder()
                .menu(assigned)
                .errors(List.of())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuCreate)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuCreate menuCreate(@InputArgument MenuCreateInput input) {
        if (input == null || input.getName() == null || input.getName().isBlank()) {
            throw new IllegalArgumentException("A menu name is required");
        }
        // The shard review (Minor · Functional Correctness, 2026-11-01
        // wave 2): the SDL declares MenuCreateInput.items, but the create
        // root only carries name/slug — a supplied items list was a SILENT
        // no-op (the file's own never-a-silent-no-op rule, the
        // rejectCollectionTarget pattern). The classified rejection keeps
        // the wire contract honest: create the menu, then add items.
        if (input.getItems() != null && !input.getItems().isEmpty()) {
            throw new IllegalArgumentException(
                    "menuCreate items is not implemented on this platform yet — "
                            + "create the menu, then use menuItemCreate");
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
                .menu(toMenuWithItems(currentAuthentication(),
                        navigationPort.updateMenu(currentAuthentication(),
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
        // The shard review (Minor · Stability & Availability, 2026-11-01
        // wave 2): the page siblings (pageBulkDelete) bound their ids
        // first; this root accepted an unbounded list into the owner.
        properties.requireBoundedList(ids, "menuBulkDelete");
        int count = navigationPort.bulkDeleteMenus(currentAuthentication(),
                decodeIds(ids, MENU_TAG, "A list of menu IDs is required"));
        return com.marketplace.app.graphql.generated.types.MenuBulkDelete.newBuilder()
                .count(count)
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuItemCreate)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuItemCreate menuItemCreate(@InputArgument MenuItemCreateInput input) {
        if (input == null || input.getName() == null || input.getName().isBlank()) {
            throw new IllegalArgumentException("A menu item name is required");
        }
        rejectCollectionTarget(input.getCollection());
        Authentication caller = currentAuthentication();
        CmsNavigationSpi.MenuItemView created = navigationPort.createMenuItem(caller,
                decodeId(input.getMenu(), MENU_TAG, "A menu ID is required"),
                input.getParent() == null || input.getParent().isBlank() ? null
                        : decodeId(input.getParent(), MENU_ITEM_TAG, "A parent item ID"),
                input.getName(), input.getUrl(),
                decodeNullableId(input.getCategory(), "Category"),
                decodeNullableId(input.getPage(), "Page"));
        // The wave-4 review (Minor · Functional Correctness): the returned
        // item renders through its own tree context — level and children
        // match what a menu read answers for the same item (the wave-4 CI
        // red pinned this path end to end).
        MenuTreeContext createdContext = menuTreeContextOf(caller, created.menuId());
        return MenuItemCreate.newBuilder()
                .menuItem(toGraphQlMenuItem(created, createdContext.byParent(),
                        createdContext.levels()))
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
        rejectCollectionTarget(input.getCollection());
        Authentication caller = currentAuthentication();
        CmsNavigationSpi.MenuItemView updated = navigationPort.updateMenuItem(caller,
                decodeId(id, MENU_ITEM_TAG, "A menu item ID is required"),
                input.getName(), input.getUrl(),
                decodeNullableId(input.getCategory(), "Category"),
                decodeNullableId(input.getPage(), "Page"));
        MenuTreeContext updatedContext = menuTreeContextOf(caller, updated.menuId());
        return MenuItemUpdate.newBuilder()
                .menuItem(toGraphQlMenuItem(updated, updatedContext.byParent(),
                        updatedContext.levels()))
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuItemDelete)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuItemDelete menuItemDelete(@InputArgument String id) {
        Authentication caller = currentAuthentication();
        CmsNavigationSpi.MenuItemView deleted = navigationPort.deleteMenuItem(caller,
                decodeId(id, MENU_ITEM_TAG, "A menu item ID is required"));
        MenuTreeContext deletedContext = menuTreeContextOf(caller, deleted.menuId());
        // The 2026-11-06 #806 round review (Minor · Functional
        // Correctness): the deleted row is absent from the POST-deletion
        // tree, so the context's levels map never carried it — the
        // rendered `level` fell back to the default 0 even for a deeply
        // nested item. The deleted row's own parentId still addresses its
        // SURVIVING parent chain (the subtree re-parents to the parent —
        // the owner's own never-orphan law), so the level is derived from
        // the parent's own computed level + 1.
        Map<UUID, Integer> levelsWithDeleted = new HashMap<>(deletedContext.levels());
        levelsWithDeleted.put(deleted.id(), deletedLevelOf(deleted, deletedContext));
        return MenuItemDelete.newBuilder()
                .menuItem(toGraphQlMenuItem(deleted, deletedContext.byParent(),
                        levelsWithDeleted))
                .errors(mappersEmpty())
                .build();
    }

    /**
     * The deleted row's own level — derived from its SURVIVING parent
     * chain in the post-deletion tree (the deletion re-parents the
     * subtree to the deleted row's parent, so every ancestor survives
     * with its own level in the context; a root-level deletion answers
     * the honest 0).
     */
    private static int deletedLevelOf(CmsNavigationSpi.MenuItemView deleted,
                                      MenuTreeContext context) {
        if (deleted.parentId() == null) {
            return 0;
        }
        Integer parentLevel = context.levels().get(deleted.parentId());
        return parentLevel == null ? 0 : parentLevel + 1;
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuItemBulkDelete)
    @RateLimiter(name = "graphqlCmsMutation")
    public com.marketplace.app.graphql.generated.types.MenuItemBulkDelete menuItemBulkDelete(
            @InputArgument List<String> ids) {
        // The shard review (2026-11-01 wave 2): the same G4 bound the
        // menu/page bulk-delete siblings apply.
        properties.requireBoundedList(ids, "menuItemBulkDelete");
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
        Authentication caller = currentAuthentication();
        CmsNavigationSpi.MenuItemView translated = navigationPort.translateMenuItem(
                caller,
                decodeId(id, MENU_ITEM_TAG, "A menu item ID is required"),
                // the wire enum's upper-case name (AR/EN) converts to the owning
                // store's lower-case language currency ('ar'/'en') at this boundary
                languageCode.name().toLowerCase(java.util.Locale.ROOT), input.getName());
        MenuTreeContext translatedContext = menuTreeContextOf(caller, translated.menuId());
        return MenuItemTranslate.newBuilder()
                .menuItem(toGraphQlMenuItem(translated, translatedContext.byParent(),
                        translatedContext.levels()))
                .errors(mappersEmpty())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.MenuItemMove)
    @RateLimiter(name = "graphqlCmsMutation")
    public MenuItemMove menuItemMove(@InputArgument String menu,
                                     @InputArgument List<MenuItemMoveInput> moves) {
        // The shard review (2026-11-01 wave 2): every move decodes two
        // global IDs into the owner's cycle-guarded batch — the same G4
        // bound the reorder roots apply.
        properties.requireBoundedList(moves, "menuItemMove");
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
        Authentication caller = currentAuthentication();
        navigationPort.moveMenuItems(caller, menuId, itemIds, parentIds, sortOrders);
        // The shard review (Major · Functional Correctness, 2026-11-01): the
        // fabricated MenuView(menuId, null, null) answered name:""/slug:""/items:[]
        // — the caller needs the ACTUAL tree after the move. Re-read the
        // menu and render it through the same items-carrying mapper the
        // single-menu reads use.
        return MenuItemMove.newBuilder()
                .menu(navigationPort.findMenuById(caller, menuId)
                        .map(view -> toMenuWithItems(caller, view)).orElse(null))
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
                                         @InputArgument MenuSortingInput sortBy,
                                         graphql.schema.DataFetchingEnvironment environment) {
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
        // The 2026-11-05 #806 round-3 review (Minor · Functional
        // Correctness): a NEGATIVE page size is a client error (the
        // classified VAL-001 family), while zero and the absent value stay
        // the documented empty page.
        if (requested < 0) {
            throw new IllegalArgumentException(
                    "menus first must not be negative");
        }
        if (requested == 0) {
            return MenuCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(pageInfoOf(null, null, false, false))
                    .build();
        }
        String field = sortBy == null ? "NAME" : sortBy.getField() == null
                ? "NAME" : sortBy.getField().name();
        boolean ascending = sortBy == null || sortBy.getDirection() == null
                || com.marketplace.app.graphql.generated.types.OrderDirection.ASC
                        .equals(sortBy.getDirection());
        org.springframework.data.domain.KeysetScrollPosition position = keysetPosition(after,
                field, MENUS_SCROLL_ID, ascending);
        CmsNavigationSpi.MenuWindow window = navigationPort.scrollMenus(currentAuthentication(),
                field, ascending, position,
                Limit.of(Math.min(requested, properties.pagination().maxPageSize())));
        // The shard review (Major · Functional Correctness, 2026-11-01):
        // the connection rendered every node through toGraphQlMenu — items
        // ALWAYS empty — so menus { edges { node { items } } } answered []
        // even for menus that carry items. Each node now renders through
        // the same items-carrying mapper the single-menu reads use (the
        // page is bounded by max-list-items/max-page-size, the same bound
        // every per-node field resolution on this surface already pays).
        Authentication menusCaller = currentAuthentication();
        // The 2026-11-06 #806 round-5 review (Minor · Performance &
        // Scalability): the item tree loads ONLY when the client actually
        // selects items — a page of menus that omits the field (the
        // common list view) pays no per-menu listMenuItems round at all.
        boolean wantsItems = environment.getSelectionSet().contains("edges/node/items");
        List<com.marketplace.app.graphql.generated.types.MenuCountableEdge> edges =
                new ArrayList<>(window.window().getContent().size());
        for (int i = 0; i < window.window().getContent().size(); i++) {
            edges.add(com.marketplace.app.graphql.generated.types.MenuCountableEdge.newBuilder()
                    .node(wantsItems
                            ? toMenuWithItems(menusCaller, window.window().getContent().get(i))
                            : toGraphQlMenu(window.window().getContent().get(i)))
                    .cursor(cursorStrategy.toCursor(KeysetCursorGate.stamped(
                            window.window().positionAt(i), MENUS_SCROLL_ID,
                            cursorProperty(field), ascending)))
                    .build());
        }
        // The shard review (Minor · Functional Correctness, 2026-11-01
        // wave 2): the connection envelope omitted the Relay cursors and
        // pinned hasPreviousPage=false — a client following endCursor
        // (Apollo's relayStylePagination) could never advance. The
        // PermissionGroupDataFetcher.toConnection envelope is the
        // surface's declared pattern: cursors from the first/last edge,
        // hasPreviousPage from the REQUESTED scroll position.
        String menusStart = edges.isEmpty() ? null : edges.get(0).getCursor();
        String menusEnd = edges.isEmpty() ? null : edges.get(edges.size() - 1).getCursor();
        return MenuCountableConnection.newBuilder()
                .edges(edges)
                .pageInfo(pageInfoOf(menusStart, menusEnd,
                        window.window().hasNext(), !position.isInitial()))
                .totalCount((int) Math.min(window.total(), Integer.MAX_VALUE))
                .build();
    }

    @DgsQuery(field = DgsConstants.QUERY.MenuItem)
    @RateLimiter(name = "graphqlCmsQuery")
    public MenuItem menuItem(@InputArgument String channel,
                             @InputArgument String id) {
        rejectChannel(channel);
        Authentication caller = currentAuthentication();
        return navigationPort.findMenuItemById(caller,
                        decodeId(id, MENU_ITEM_TAG, "A menu item ID is required"))
                .map(view -> {
                    // The wave-4 review (Minor · Functional Correctness):
                    // the flat mapper answered level 0 with no children for
                    // EVERY item — the root now resolves the item's own
                    // tree context exactly like a rendered menu node.
                    MenuTreeContext context = menuTreeContextOf(caller, view.menuId());
                    return toGraphQlMenuItem(view, context.byParent(), context.levels());
                })
                .orElse(null);
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
        if (requested < 0) {
            // The 2026-11-05 #806 round-3 review: a NEGATIVE page size is
            // the classified VAL-001 client error; zero stays the
            // documented empty page.
            throw new IllegalArgumentException(
                    "menuItems first must not be negative");
        }
        if (requested == 0) {
            return MenuItemCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(pageInfoOf(null, null, false, false))
                    .build();
        }
        org.springframework.data.domain.KeysetScrollPosition position = keysetPosition(after,
                "NAME", MENU_ITEMS_SCROLL_ID, true);
        CmsNavigationSpi.MenuItemWindow window = navigationPort.scrollMenuItems(
                currentAuthentication(), "NAME", true, position,
                Limit.of(Math.min(requested, properties.pagination().maxPageSize())));
        // The wave-4 review (Minor · Functional Correctness): the page's
        // items may span several menus — the tree context is built ONCE
        // per menuId (the window content is menu-ordered) and every node
        // renders with its real level/children.
        Authentication caller = currentAuthentication();
        Map<UUID, MenuTreeContext> contexts = new HashMap<>();
        List<com.marketplace.app.graphql.generated.types.MenuItemCountableEdge> edges =
                new ArrayList<>(window.window().getContent().size());
        for (int i = 0; i < window.window().getContent().size(); i++) {
            CmsNavigationSpi.MenuItemView item = window.window().getContent().get(i);
            MenuTreeContext context = contexts.computeIfAbsent(item.menuId(),
                    menuId -> menuTreeContextOf(caller, menuId));
            edges.add(com.marketplace.app.graphql.generated.types.MenuItemCountableEdge.newBuilder()
                    .node(toGraphQlMenuItem(item, context.byParent(), context.levels()))
                    .cursor(cursorStrategy.toCursor(KeysetCursorGate.stamped(
                            window.window().positionAt(i), MENU_ITEMS_SCROLL_ID,
                            cursorProperty("NAME"), true)))
                    .build());
        }
        // The shard review (2026-11-01 wave 2): the same Relay envelope the
        // menus connection carries (the PermissionGroup toConnection
        // pattern — cursors from the edges, hasPreviousPage from the
        // requested scroll position).
        String itemsStart = edges.isEmpty() ? null : edges.get(0).getCursor();
        String itemsEnd = edges.isEmpty() ? null : edges.get(edges.size() - 1).getCursor();
        return MenuItemCountableConnection.newBuilder()
                .edges(edges)
                .pageInfo(pageInfoOf(itemsStart, itemsEnd,
                        window.window().hasNext(), !position.isInitial()))
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
            String after, String sortField, String scrollId, boolean ascending) {
        // B18 (CAT-06 — the 2026-11-03 adversarial review, review-pr-808):
        // the scrolls' own identity and direction ride the cursor's
        // fingerprint — a menus cursor never passes the menuItems gate
        // (they shared one identity before) and an ASC cursor never
        // passes the DESC query.
        String property = "SLUG".equals(sortField) ? "slug" : "name";
        return KeysetCursorGate.forwardKeyset(cursorStrategy, after, property,
                KeysetCursorGate::textValue, scrollId, ascending);
    }

    /** The executed sort's owning property — the cursor's fingerprint axis. */
    private static String cursorProperty(String sortField) {
        return "SLUG".equals(sortField) ? "slug" : "name";
    }

    /**
     * The menu's item-tree context — the by-parent grouping and the
     * computed levels one menu's whole item set carries. Shared by the
     * menu node rendering ({@link #toMenuWithItems}) and the
     * {@code MenuItem.parent} field resolution (the shard review
     * 2026-11-01 wave 2: the parent's own level/children need the tree
     * context, exactly like a rendered menu node).
     */
    private record MenuTreeContext(Map<UUID, List<CmsNavigationSpi.MenuItemView>> byParent,
                                    Map<UUID, Integer> levels) {
    }

    private MenuTreeContext menuTreeContextOf(Authentication caller, UUID menuId) {
        List<CmsNavigationSpi.MenuItemView> items = navigationPort.listMenuItems(caller, menuId);
        Map<UUID, List<CmsNavigationSpi.MenuItemView>> byParent = new HashMap<>();
        for (CmsNavigationSpi.MenuItemView item : items) {
            byParent.computeIfAbsent(item.parentId(), key -> new ArrayList<>()).add(item);
        }
        Map<UUID, Integer> levels = new HashMap<>();
        for (CmsNavigationSpi.MenuItemView item : items) {
            levels.put(item.id(), levelOf(item, byParent));
        }
        return new MenuTreeContext(byParent, levels);
    }

    private Menu toMenuWithItems(Authentication caller, CmsNavigationSpi.MenuView view) {
        MenuTreeContext context = menuTreeContextOf(caller, view.id());
        List<MenuItem> roots = context.byParent().getOrDefault(null, List.of()).stream()
                .map(item -> toGraphQlMenuItem(item, context.byParent(), context.levels()))
                .toList();
        return Menu.newBuilder()
                .id(globalIdCodec.encode(MENU_TAG, view.id()))
                .name(view.name() == null ? "" : view.name())
                .slug(view.slug() == null ? "" : view.slug())
                .items(roots)
                .build();
    }

    private static int levelOf(CmsNavigationSpi.MenuItemView item,
                               Map<UUID, List<CmsNavigationSpi.MenuItemView>> byParent) {
        // The flat set is ordered by (parent, position); levels computed by
        // walking the parent chain — the owner's cycle guard guarantees it
        // terminates. The wave-4 review's quadratic-scan nitpick: the
        // parent LOOKUP rides an id-indexed view built once per call
        // (O(1) per hop) instead of a full values() scan per hop.
        Map<UUID, CmsNavigationSpi.MenuItemView> byId = new HashMap<>();
        for (List<CmsNavigationSpi.MenuItemView> siblings : byParent.values()) {
            for (CmsNavigationSpi.MenuItemView candidate : siblings) {
                byId.put(candidate.id(), candidate);
            }
        }
        int level = 0;
        UUID parentId = item.parentId();
        // The 2026-11-05 #806 round-3 review (Minor · Functional
        // Correctness): the hop CAP (64) silently misreported the level
        // of any chain deeper than 64 — the creator and mover impose no
        // depth bound, so the rendered `level` must answer the true
        // depth. The termination guard is the honest one: a visited set
        // stops a cycle at its first repeat (the owner's cycle guard
        // makes the case unreachable; the walk stays total regardless)
        // without capping legitimate depth.
        Set<UUID> visited = new HashSet<>();
        visited.add(item.id());
        while (parentId != null && visited.add(parentId)) {
            level++;
            CmsNavigationSpi.MenuItemView parent = byId.get(parentId);
            parentId = parent == null ? null : parent.parentId();
        }
        return level;
    }

    private MenuItem toGraphQlMenuItem(CmsNavigationSpi.MenuItemView view) {
        return toGraphQlMenuItem(view, Map.of(), Map.of());
    }

    private MenuItem toGraphQlMenuItem(CmsNavigationSpi.MenuItemView view,
                                       Map<UUID, List<CmsNavigationSpi.MenuItemView>> byParent,
                                       Map<UUID, Integer> levels) {
        List<MenuItem> children = byParent.getOrDefault(view.id(), List.of()).stream()
                .map(child -> toGraphQlMenuItem(child, byParent, levels))
                .toList();
        // The shard review (Minor · Functional Correctness, 2026-11-01
        // wave 2): the SDL pins Menu.items: [MenuItem!]! — the embedded
        // menu stub left it unset (a non-null violation the moment a
        // client selected menuItem { menu { items { id } } }), and the
        // fabricated name:""/slug:"" reached the wire whenever the stub
        // itself was served. The stub is now the representable FLOOR (id +
        // items:[]) and the real menu/parent resolve through the field
        // resolvers below (menuItemMenu/menuItemParent) when selected.
        return MenuItem.newBuilder()
                .id(globalIdCodec.encode(MENU_ITEM_TAG, view.id()))
                .menu(Menu.newBuilder()
                        .id(globalIdCodec.encode(MENU_TAG, view.menuId()))
                        .name("").slug("")
                        .items(List.of())
                        .build())
                ._parent(view.parentId() == null ? null : MenuItem.newBuilder()
                        .id(globalIdCodec.encode(MENU_ITEM_TAG, view.parentId()))
                        .menu(Menu.newBuilder().id(globalIdCodec.encode(MENU_TAG, view.menuId()))
                                .name("").slug("").items(List.of()).build())
                        .name("")
                        .level(levels.getOrDefault(view.parentId(), 0))
                        .build())
                .name(view.name())
                .url(view.url())
                .level(levels.getOrDefault(view.id(), 0))
                .children(children.isEmpty() ? null : children)
                .build();
    }

    /**
     * The {@code MenuItem.menu} read-back — the field resolver the DGS
     * engine calls ONLY when the selection asks for the item's menu (the
     * AddressDataFetcher User.addresses precedent). The stub the mapper
     * builds carries the menu's typed global id; the resolver decodes it
     * and renders the REAL menu (name/slug/items) through the same
     * items-carrying mapper the menu reads use. A menu deleted
     * concurrently answers the stub floor (id + items:[]) — the menu
     * field is non-null on the wire.
     *
     * <p>NO rate limiting on FIELD fetchers (the wave-1 Collection.products
     * lesson): the root resolver already paid the query budget — a page of
     * items must not fail mid-page.</p>
     */
    @DgsData(parentType = DgsConstants.MENUITEM.TYPE_NAME, field = DgsConstants.MENUITEM.Menu)
    public Menu menuItemMenu(DataFetchingEnvironment environment) {
        MenuItem item = environment.getSource();
        if (item == null || item.getMenu() == null || item.getMenu().getId() == null) {
            return null;
        }
        // The wave-4 CI evidence (INT-001 on menuItemCreate.menuItem.menu):
        // field-level completion runs on threads the SecurityContextHolder
        // ThreadLocal does not reach — the caller rides the request-scoped
        // MarketplaceGraphQlContext (the official DGS custom context), the
        // userAddresses precedent's thread-free completion shape.
        Authentication caller = MarketplaceGraphQlContext.callerOf(environment);
        if (caller == null || !caller.isAuthenticated()) {
            return item.getMenu();
        }
        return navigationPort.findMenuById(caller,
                        decodeId(item.getMenu().getId(), MENU_TAG, "A menu ID is required"))
                .map(view -> toMenuWithItems(caller, view))
                .orElse(item.getMenu());
    }

    /**
     * The {@code MenuItem.category} read-back — the wave-3 review (Minor ·
     * Functional Correctness): the write paths (menuItemCreate/Update)
     * accept and persist the category binding, but no path ever rendered
     * it — the field answered null even when set. The resolver re-reads
     * the item (the view carries the categoryId) and resolves the REAL
     * category through the catalog owner's own lookup; an item without a
     * category answers null (the nullable field's honest absence). No
     * rate limiting on field fetchers (the wave-1 lesson).
     */
    @DgsData(parentType = DgsConstants.MENUITEM.TYPE_NAME, field = DgsConstants.MENUITEM.Category)
    public com.marketplace.app.graphql.generated.types.Category menuItemCategory(
            DataFetchingEnvironment environment) {
        MenuItem item = environment.getSource();
        if (item == null || item.getId() == null) {
            return null;
        }
        // The request-scoped caller (the wave-4 CI evidence — the holder
        // ThreadLocal is absent on field-level completion threads).
        Authentication caller = MarketplaceGraphQlContext.callerOf(environment);
        if (caller == null || !caller.isAuthenticated()) {
            return null;
        }
        return navigationPort.findMenuItemById(caller,
                        decodeId(item.getId(), MENU_ITEM_TAG, "A menu item ID is required"))
                .flatMap(view -> view.categoryId() == null ? java.util.Optional.empty()
                        : categoryReadSpi.findCategoryById(view.categoryId(), caller))
                .map(categoryMapper::toGraphQlCategory)
                .orElse(null);
    }

    /**
     * The {@code MenuItem.page} read-back — the same wave-3 finding: the
     * page binding renders through the shared CmsPageGraphQlMapper (the
     * mapping extracted from CmsPageDataFetcher for exactly this reuse);
     * an item without a page answers null.
     */
    @DgsData(parentType = DgsConstants.MENUITEM.TYPE_NAME, field = DgsConstants.MENUITEM.Page)
    public com.marketplace.app.graphql.generated.types.Page menuItemPage(
            DataFetchingEnvironment environment) {
        MenuItem item = environment.getSource();
        if (item == null || item.getId() == null) {
            return null;
        }
        // The request-scoped caller (the wave-4 CI evidence — the holder
        // ThreadLocal is absent on field-level completion threads).
        Authentication caller = MarketplaceGraphQlContext.callerOf(environment);
        if (caller == null || !caller.isAuthenticated()) {
            return null;
        }
        return navigationPort.findMenuItemById(caller,
                        decodeId(item.getId(), MENU_ITEM_TAG, "A menu item ID is required"))
                .flatMap(view -> view.pageId() == null ? java.util.Optional.empty()
                        : cmsPageSpi.findPageById(caller, view.pageId()))
                .map(pageMapper::toGraphQlPage)
                .orElse(null);
    }

    /**
     * The {@code MenuItem.parent} read-back — the honest no-parent stays
     * the mapper's null (the field is nullable on the wire; a parentless
     * item answers null, never a fabricated stub). A parent that resolves
     * renders through the SAME tree context a rendered menu node uses
     * (its own level and children are real, not the flat level-0/no-
     * children stub); a parent deleted concurrently answers null — the
     * honest absence.
     */
    @DgsData(parentType = DgsConstants.MENUITEM.TYPE_NAME, field = DgsConstants.MENUITEM.Parent)
    public MenuItem menuItemParent(DataFetchingEnvironment environment) {
        MenuItem item = environment.getSource();
        if (item == null || item.getParent() == null || item.getParent().getId() == null) {
            return null;
        }
        // The wave-4 #806 review (Minor · Stability & Availability): this is
        // a FIELD-level completion resolver — the engine fans it out onto
        // threads the SecurityContextHolder ThreadLocal does not reach (the
        // wave-4 INT-001 evidence), so the ThreadLocal read both breaks and
        // (before the context's anonymous normalization) could answer an
        // AnonymousAuthenticationToken. The request-scoped context is the
        // sanctioned read (the AddressDataFetcher.userAddresses precedent);
        // an absent or anonymous caller answers the honest null floor.
        Authentication caller = MarketplaceGraphQlContext.callerOf(environment);
        if (caller == null || !caller.isAuthenticated()) {
            return null;
        }
        return navigationPort.findMenuItemById(caller,
                        decodeId(item.getParent().getId(), MENU_ITEM_TAG, "A parent item ID"))
                .map(parent -> {
                    MenuTreeContext context = menuTreeContextOf(caller, parent.menuId());
                    return toGraphQlMenuItem(parent, context.byParent(), context.levels());
                })
                .orElse(null);
    }

    /**
     * The {@code MenuItem.translation(languageCode)} read-back — the wave-11
     * #806 review (Major · Functional Correctness): the SDL declared the
     * field but no resolver ever wired it, so every selection answered the
     * mapper's silent null. The owning read source EXISTS — the item
     * projection carries the per-locale name translations the console owner
     * loads (keyed by language code, each with its owning row id) — so the
     * field resolves through the SAME seam every menu-item read uses: the
     * source item's id re-reads via {@code findMenuItemById} and the
     * addressed language's translation renders with its row id (the Saleor
     * {@code MenuItemTranslation.id} identity — the attribute-translation
     * precedent's encode scheme). A language with no translation answers the
     * honest null (Saleor's own semantics); an item deleted concurrently
     * answers null too.
     *
     * <p>This is a FIELD-level completion resolver — the caller reads from
     * the REQUEST-SCOPED context (the wave-4 INT-001 evidence: the
     * SecurityContext ThreadLocal does not reach the engine's completion
     * threads); an absent caller answers the honest null floor. The per-node
     * re-read joins the pageTypeAttributes/Collection.products guarded-N+1
     * advisory class (maxPageSize caps the fan-out).</p>
     */
    @DgsData(parentType = DgsConstants.MENUITEM.TYPE_NAME,
             field = DgsConstants.MENUITEM.Translation)
    public MenuItemTranslation menuItemTranslation(DataFetchingEnvironment environment,
                                                   @InputArgument("languageCode")
                                                   LanguageCodeEnum languageCode) {
        MenuItem item = environment.getSource();
        if (item == null || item.getId() == null || languageCode == null) {
            return null;
        }
        Authentication caller = MarketplaceGraphQlContext.callerOf(environment);
        if (caller == null || !caller.isAuthenticated()) {
            return null;
        }
        return navigationPort.findMenuItemById(caller,
                        decodeId(item.getId(), MENU_ITEM_TAG, "A menu item ID is required"))
                // the projection's translation map is keyed by the stored
                // lower-case codes; the wire form converts at this boundary
                .map(view -> view.translations().get(languageCode.name().toLowerCase(java.util.Locale.ROOT)))
                .map(translation -> MenuItemTranslation.newBuilder()
                        .id(globalIdCodec.encode("MenuItemTranslation", translation.id()))
                        .name(translation.name())
                        .language(languageCode)
                        .build())
                .orElse(null);
    }

    private Menu toGraphQlMenu(CmsNavigationSpi.MenuView view) {
        return Menu.newBuilder()
                .id(globalIdCodec.encode(MENU_TAG, view.id()))
                .name(view.name() == null ? "" : view.name())
                .slug(view.slug() == null ? "" : view.slug())
                .items(List.of())
                .build();
    }

    /**
     * The connection envelope's page info — the PermissionGroupDataFetcher
     * toConnection pattern (the shard review 2026-11-01 wave 2): the
     * Relay cursors from the first/last edge and hasPreviousPage from the
     * REQUESTED scroll position (a scroll that started after a known
     * element has elements before it).
     */
    private static com.marketplace.app.graphql.generated.types.PageInfo pageInfoOf(
            String startCursor, String endCursor, boolean hasNext, boolean hasPrevious) {
        return com.marketplace.app.graphql.generated.types.PageInfo.newBuilder()
                .hasNextPage(hasNext)
                .hasPreviousPage(hasPrevious)
                .startCursor(startCursor)
                .endCursor(endCursor)
                .build();
    }

    private static List<com.marketplace.app.graphql.generated.types.MenuError> mappersEmpty() {
        return List.of();
    }

    private static void rejectCollectionTarget(String collection) {
        if (collection != null && !collection.isBlank()) {
            throw new IllegalArgumentException(
                    "Collection-backed menu items are not implemented on this platform yet");
        }
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
