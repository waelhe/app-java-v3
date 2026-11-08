package com.marketplace.console;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the CodeRabbit round on PR #696): the navigation owner's guards
 * — the oneof target switch on update (B4: a URL write clears the category
 * and page pointers), the deterministic relative move (B6:
 * remove-and-reinsert, never the UUID tie-breaker), and the per-menu
 * root-name invariant on EVERY path that creates, renames, or promotes a
 * root (m2).
 */
@ExtendWith(MockitoExtension.class)
class CmsNavigationAdminServiceTest {

    @Mock
    private CmsMenuRepository menuRepository;
    @Mock
    private CmsMenuItemRepository itemRepository;
    @Mock
    private CmsMenuItemTranslationRepository itemTranslationRepository;
    @Mock
    private CmsNavigationSlotRepository slotRepository;
    @Mock
    private CmsPageRepository pageRepository;

    private CmsNavigationAdminService service;

    private Authentication admin;
    private UUID menuId;

    @BeforeEach
    void setUp() {
        service = new CmsNavigationAdminService(menuRepository, itemRepository,
                itemTranslationRepository, slotRepository, pageRepository);
        admin = new UsernamePasswordAuthenticationToken("admin@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        menuId = UUID.randomUUID();
    }

    private CmsMenuItem root(String name, int position) {
        CmsMenuItem item = CmsMenuItem.create(menuId, null, name);
        item.moveTo(position);
        return item;
    }

    private CmsMenuItem child(CmsMenuItem parent, String name, int position) {
        CmsMenuItem item = CmsMenuItem.create(menuId, parent.getId(), name);
        item.moveTo(position);
        return item;
    }

    @Test
    void createMenuItemRejectsADuplicateRootName() {
        when(menuRepository.findByIdForUpdate(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findByMenuIdAndNameAndParentIdIsNull(menuId, "Home"))
                .thenReturn(Optional.of(root("Home", 0)));

        assertThatThrownBy(() -> service.createMenuItem(admin, menuId, null,
                "Home", null, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("root menu item with this name");
    }

    @Test
    void updateMenuItemSwitchingToAUrlClearsTheOtherTargets() {
        UUID id = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        CmsMenuItem row = root("Docs", 0);
        row.apply(null, null, null, pageId);
        assertThat(row.getPageId()).isEqualTo(pageId);
        when(itemRepository.findById(id)).thenReturn(Optional.of(row));

        // B4: the update sets ONLY a URL — the merged row must carry the
        // URL alone, or the V283 at-most-one-target CHECK rejects the
        // flush with a raw database error.
        service.updateMenuItem(admin, id, null, "https://docs.example.com", null, null);

        assertThat(row.getUrl()).isEqualTo("https://docs.example.com");
        assertThat(row.getCategoryId()).isNull();
        assertThat(row.getPageId()).isNull();
    }

    @Test
    void updateMenuItemRenamingARootCarriesTheRootNameInvariant() {
        UUID id = UUID.randomUUID();
        CmsMenuItem row = root("Old", 0);
        when(itemRepository.findById(id)).thenReturn(Optional.of(row));
        when(itemRepository.findByMenuIdAndNameAndParentIdIsNull(menuId, "Taken"))
                .thenReturn(Optional.of(root("Taken", 1)));

        assertThatThrownBy(() -> service.updateMenuItem(admin, id, "Taken", null, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("root menu item with this name");
    }

    @Test
    void moveMenuItemsAppliesDeterministicRemoveAndReinsert() {
        CmsMenuItem a = root("A", 0);
        CmsMenuItem b = root("B", 1);
        CmsMenuItem c = root("C", 2);
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(c.getId())).thenReturn(Optional.of(c));
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(a, b, c));

        // B6: C moves up one slot — the expected dense order is A, C, B.
        // The previous position+delta arithmetic tied C with B and let the
        // UUID decide; remove-and-reinsert never ties.
        service.moveMenuItems(admin, menuId, List.of(c.getId()),
                java.util.Arrays.asList((UUID) null), List.of(-1));

        assertThat(a.getPosition()).isZero();
        assertThat(c.getPosition()).isEqualTo(1);
        assertThat(b.getPosition()).isEqualTo(2);
    }

    @Test
    void moveMenuItemsToRootCarriesTheRootNameInvariant() {
        CmsMenuItem parent = root("Parent", 0);
        CmsMenuItem item = child(parent, "Home", 0);
        CmsMenuItem stayingHomeRoot = root("Home", 1);
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        // The 2026-11-05 #810 round review: the root-name invariant
        // evaluates the batch's FINAL state — the STAYING roots are the
        // moved set excluded (their post-batch homes are already decided
        // in memory), so the final-state query is the roots list, not the
        // per-name lookup.
        when(itemRepository.findAllByMenuIdAndParentIdIsNullOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(parent, stayingHomeRoot));

        // m2: the move-to-root path previously flushed straight into the
        // V283 partial unique index's raw database error.
        assertThatThrownBy(() -> service.moveMenuItems(admin, menuId,
                List.of(item.getId()), java.util.Arrays.asList((UUID) null), List.of(0)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("root menu item with this name");
    }

    /**
     * The 2026-11-05 #810 round review (Minor · Functional Correctness):
     * a batch that FREES a root name (its holder moves UNDER another
     * item) and grants it to another item in the SAME batch is a legal
     * end state — the old per-move intermediate check rejected it at the
     * first move.
     */
    @Test
    void moveMenuItemsEvaluatesTheBatchFinalStateNotTheIntermediateOne() {
        CmsMenuItem homeRoot = root("Home", 0);
        CmsMenuItem holder = root("Holder", 1);
        CmsMenuItem homeChild = child(holder, "Home", 0);
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(homeRoot.getId())).thenReturn(Optional.of(homeRoot));
        when(itemRepository.findById(holder.getId())).thenReturn(Optional.of(holder));
        when(itemRepository.findById(homeChild.getId())).thenReturn(Optional.of(homeChild));
        when(itemRepository.findAllByMenuIdAndParentIdIsNullOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(homeRoot, holder));
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(homeRoot, holder, homeChild));

        // homeRoot moves UNDER holder; homeChild moves to the ROOT group —
        // the intermediate state (homeRoot still a root when homeChild
        // joins) used to conflict; the FINAL state has exactly one root
        // "Home".
        service.moveMenuItems(admin, menuId,
                List.of(homeRoot.getId(), homeChild.getId()),
                java.util.Arrays.asList(holder.getId(), null), List.of(0, 0));

        assertThat(homeRoot.getParentId()).isEqualTo(holder.getId());
        assertThat(homeChild.getParentId()).isNull();
    }

    /**
     * The 2026-11-05 #810 round review: the three lists are one-per-item
     * by contract — a length mismatch answers the classified validation
     * error before any database read.
     */
    @Test
    void moveMenuItemsRefusesAMismatchedListLength() {
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));

        assertThatThrownBy(() -> service.moveMenuItems(admin, menuId,
                List.of(UUID.randomUUID()), List.of(), List.of(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one entry per item");
        assertThatThrownBy(() -> service.moveMenuItems(admin, menuId,
                List.of(UUID.randomUUID()), java.util.Arrays.asList((UUID) null), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one entry per item");
    }

    @Test
    void deleteMenuItemPromotesTheSubtreeUnderTheDeletedParent() {
        CmsMenuItem parent = root("Parent", 0);
        CmsMenuItem kid = child(parent, "Kid", 0);
        // The 2026-11-08 #810 round-5 review: the locked load + the
        // delete-before-promote order.
        when(itemRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(parent, kid));

        service.deleteMenuItem(admin, parent.getId());

        assertThat(kid.getParentId()).isNull();
        verify(itemRepository).delete(parent);
    }

    @Test
    void deleteMenuItemRefusesARootPromotionThatWouldCollide() {
        CmsMenuItem parent = root("Parent", 0);
        CmsMenuItem kid = child(parent, "Home", 0);
        // The 2026-11-08 #810 round-5 review: the locked load + the
        // delete-before-promote order.
        when(itemRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(parent, kid));
        when(itemRepository.findByMenuIdAndNameAndParentIdIsNull(menuId, "Home"))
                .thenReturn(Optional.of(root("Home", 1)));

        // m2: the deleted root's children promote INTO the root group — a
        // colliding name answers the classified conflict, never the raw
        // database error.
        assertThatThrownBy(() -> service.deleteMenuItem(admin, parent.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("root menu item with this name");
        verify(itemRepository, never()).delete(any(CmsMenuItem.class));
    }

    /**
     * The 2026-11-05 #810 round review: the deleted root no longer
     * occupies the root-name space for its promoted children — deleting
     * root "Home" with a child also named "Home" is a legal promotion.
     */
    @Test
    void deleteMenuItemPromotesAChildNamedLikeTheDoomedRoot() {
        CmsMenuItem parent = root("Home", 0);
        CmsMenuItem kid = child(parent, "Home", 0);
        // The 2026-11-08 #810 round-5 review: the locked load + the
        // delete-before-promote order.
        when(itemRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(parent, kid));
        when(itemRepository.findByMenuIdAndNameAndParentIdIsNull(menuId, "Home"))
                .thenReturn(Optional.of(parent));

        service.deleteMenuItem(admin, parent.getId());

        assertThat(kid.getParentId()).isNull();
        verify(itemRepository).delete(parent);
    }

    /**
     * The 2026-11-05 #810 round review: the name-length bounds answer the
     * classified validation error on the update paths (updateMenu,
     * updateMenuItem, translateMenuItem) — never the flush's raw database
     * error.
     */
    @Test
    void theNameLengthBoundAnswersTheClassifiedErrorOnEveryWritePath() {
        String longName = "N".repeat(256);
        CmsMenu menu = CmsMenu.create("Main", "main");
        UUID menuRowId = UUID.randomUUID();
        when(menuRepository.findById(menuRowId)).thenReturn(Optional.of(menu));

        CmsMenuItem item = root("Item", 0);
        UUID itemRowId = UUID.randomUUID();
        when(itemRepository.findById(itemRowId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.updateMenu(admin, menuRowId, longName, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 255");
        assertThatThrownBy(() -> service.updateMenuItem(admin, itemRowId, longName,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 255");
        assertThatThrownBy(() -> service.translateMenuItem(admin, itemRowId, "en", longName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 255");
    }

    /**
     * The 2026-11-05 #810 round review (Minor · Security & Privacy): the
     * ADMIN verdict requires an AUTHENTICATED principal — an
     * unauthenticated Authentication object carrying ROLE_ADMIN no longer
     * passes the role-only check.
     */
    @Test
    void anUnauthenticatedPrincipalCarryingTheAdminRoleIsRefused() {
        // An Authentication object that carries ROLE_ADMIN but answers
        // isAuthenticated()=false (the shape the security chain can hand
        // through on a failed or forged login) — the role-only check used
        // to let it administrate.
        org.springframework.security.core.Authentication unauthenticated =
                org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class);
        org.mockito.Mockito.when(unauthenticated.isAuthenticated()).thenReturn(false);
        org.mockito.Mockito.lenient().when(unauthenticated.getAuthorities())
                .thenReturn((java.util.Collection) List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThatThrownBy(() -> service.findMenuById(unauthenticated, UUID.randomUUID()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    /**
     * The 2026-11-05 #810 round review: the bulk delete orders DEPTH-FIRST
     * — a parent and its child delete in one batch without the parent's
     * deletion promoting the child into a root-name conflict first.
     */
    @Test
    void bulkDeleteMenuItemsDeletesTheDeepestNodesFirst() {
        CmsMenuItem parent = root("Home", 0);
        CmsMenuItem kid = child(parent, "Home", 0);
        CmsMenuItem otherRoot = root("Home", 1);
        // The parent's chain: root (depth 0); the kid: depth 1.
        // depthOf keeps the plain findById; the locked delete loads ride
        // findByIdForUpdate (the 2026-11-08 round-5 law).
        when(itemRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(itemRepository.findById(kid.getId())).thenReturn(Optional.of(kid));
        when(itemRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));
        when(itemRepository.findByIdForUpdate(kid.getId()))
                .thenReturn(Optional.of(kid));
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(parent, otherRoot, kid));
        // The kid's promotion (parent still alive at that point in the OLD
        // order) would collide with otherRoot; deepest-first deletes the
        // kid before the parent, so no promotion ever happens.
        when(itemRepository.existsById(parent.getId())).thenReturn(true, true, false);
        when(itemRepository.existsById(kid.getId())).thenReturn(true, false);

        int count = service.bulkDeleteMenuItems(admin, List.of(parent.getId(), kid.getId()));

        assertThat(count).isEqualTo(2);
        verify(itemRepository).delete(kid);
        verify(itemRepository).delete(parent);
    }

    @Test
    void moveMenuItemsRejectsTheCycle() {
        CmsMenuItem parent = root("Parent", 0);
        CmsMenuItem item = child(parent, "Kid", 0);
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(itemRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        // The ancestor walk: the parent chain from `parent` reaches item.
        when(itemRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        // Moving the PARENT under its own descendant would create a cycle.
        assertThatThrownBy(() -> service.moveMenuItems(admin, menuId,
                List.of(parent.getId()), List.of(item.getId()), List.of(0)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cycle");
    }


    @Test
    void scrollMenusRejectsTheUnboundedLimit() {
        // The W31 hostile-review recommendation, proven by regression: the
        // seam-level bound the catalog and pricing scrolls already carry.
        assertThatThrownBy(() -> service.scrollMenus(admin, "NAME", true,
                org.springframework.data.domain.ScrollPosition.keyset(),
                org.springframework.data.domain.Limit.unlimited()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A positive page size is required");
        assertThatThrownBy(() -> service.scrollMenus(admin, "NAME", true,
                org.springframework.data.domain.ScrollPosition.keyset(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A positive page size is required");
    }

    @Test
    void scrollMenusRejectsAnUnknownSortField() {
        // The honest classification the sibling method already documents:
        // never a silent fallback to the name sort for a vocabulary the
        // owner does not carry.
        assertThatThrownBy(() -> service.scrollMenus(admin, "POSITION", true,
                org.springframework.data.domain.ScrollPosition.keyset(),
                org.springframework.data.domain.Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported menu sort field");
    }

    @Test
    void scrollMenuItemsRejectsTheUnboundedLimit() {
        // The W31 hostile-review recommendation, proven by regression: the
        // seam-level bound the catalog and pricing scrolls already carry.
        assertThatThrownBy(() -> service.scrollMenuItems(admin, "NAME", true,
                org.springframework.data.domain.ScrollPosition.keyset(),
                org.springframework.data.domain.Limit.unlimited()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A positive page size is required");
        assertThatThrownBy(() -> service.scrollMenuItems(admin, "NAME", true,
                org.springframework.data.domain.ScrollPosition.keyset(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A positive page size is required");
    }

    @Test
    void scrollMenuItemsRejectsAnUnknownSortField() {
        // The honest classification: never a silent fallback to the name
        // sorts for a vocabulary the owner does not carry.
        assertThatThrownBy(() -> service.scrollMenuItems(admin, "POSITION", true,
                org.springframework.data.domain.ScrollPosition.keyset(),
                org.springframework.data.domain.Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported menu item sort field");
    }

    @Test
    void createMenuItemRequiresAMenu() {
        when(menuRepository.findByIdForUpdate(menuId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createMenuItem(admin, menuId, null,
                "Home", null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * B23 (IDP-05 — the 2026-11-03 adversarial review, review-pr-806): the
     * FULL admin sweep — every public leg of CmsNavigationAdminService,
     * invoked with a CONSUMER caller and inert arguments, must answer
     * Spring Security's own AccessDeniedException BEFORE any argument is
     * touched (the programmatic requireAdmin gate leads every leg; a
     * future leg that forgets it fails here on its first null-argument
     * symptom instead). A primitive parameter cannot carry null —
     * Method.invoke would fail its unboxing BEFORE the leg's gate could
     * answer — so primitive slots ride their zero-value default (see
     * {@link #inert(Class)}).
     */
    @Test
    void everyLegIsAdminGated() {
        Authentication consumer = new UsernamePasswordAuthenticationToken("m@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
        for (java.lang.reflect.Method leg : CmsNavigationAdminService.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(leg.getModifiers()) || leg.isSynthetic()) {
                continue;
            }
            Object[] args = new Object[leg.getParameterCount()];
            if (args.length > 0) {
                args[0] = consumer;
            }
            for (int i = 1; i < args.length; i++) {
                args[i] = inert(leg.getParameterTypes()[i]);
            }
            assertThatThrownBy(() -> leg.invoke(service, args))
                    .as("%s.%s must refuse a CONSUMER caller at its gate",
                            "CmsNavigationAdminService", leg.getName())
                    .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                    .hasCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }

    /**
     * The reflection harness's inert argument for one parameter slot: a
     * primitive slot cannot carry null — Method.invoke would fail the
     * unboxing with IllegalArgumentException BEFORE the leg's gate could
     * answer — so it rides its zero-value default instead (the CI round on
     * the B23 sweep: bulkUpdateCustomers' {@code boolean ignoreFailed}).
     * Every reference slot stays null: a leg that touches its arguments
     * before the gate still fails on its first null-argument symptom,
     * exactly as the sweep promises.
     */
    private static Object inert(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        throw new IllegalArgumentException("Unknown primitive type: " + type);
    }

    // ---------------------------------------------------------------------
    // The read/scroll/translate/delete legs (the coverage round)
    // ---------------------------------------------------------------------

    @Test
    void theMenuReadLegsAnswerTheRegistry() {
        CmsMenu menu = CmsMenu.create("Main", "main");
        when(menuRepository.findById(menu.getId())).thenReturn(Optional.of(menu));
        when(menuRepository.findBySlug("main")).thenReturn(Optional.of(menu));
        when(menuRepository.findFirstByNameOrderByIdAsc("Main")).thenReturn(Optional.of(menu));

        assertThat(service.findMenuById(admin, menu.getId())).isPresent();
        assertThat(service.findMenuBySlug(admin, "main")).isPresent();
        assertThat(service.findMenuByName(admin, "Main")).isPresent();
        assertThat(service.findMenuBySlug(admin, "missing")).isEmpty();
    }

    @Test
    void scrollMenusDispatchesBothSortBranches() {
        CmsMenu menu = CmsMenu.create("Main", "main");
        org.springframework.data.domain.Window<CmsMenu> window =
                org.springframework.data.domain.Window.from(List.of(menu),
                        index -> org.springframework.data.domain.ScrollPosition.offset(index),
                        true);
        when(menuRepository.findAllByOrderByNameAscIdAsc(any(), any())).thenReturn(window);
        when(menuRepository.findAllByOrderByNameDescIdDesc(any(), any())).thenReturn(window);
        when(menuRepository.findAllByOrderBySlugAscIdAsc(any(), any())).thenReturn(window);
        when(menuRepository.findAllByOrderBySlugDescIdDesc(any(), any())).thenReturn(window);
        when(menuRepository.count()).thenReturn(1L);

        assertThat(service.scrollMenus(admin, "NAME", true,
                org.springframework.data.domain.ScrollPosition.keyset(),
                org.springframework.data.domain.Limit.of(10)).window().hasNext()).isTrue();
        for (String field : new String[] {"NAME", "SLUG"}) {
            for (boolean ascending : new boolean[] {true, false}) {
                service.scrollMenus(admin, field, ascending,
                        org.springframework.data.domain.ScrollPosition.keyset(),
                        org.springframework.data.domain.Limit.of(10));
            }
        }
    }

    @Test
    void theItemReadLegsAnswerTheRegistry() {
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        CmsMenuItem item = root("Home", 0);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(item));
        when(itemTranslationRepository.findAllByMenuItemId(item.getId()))
                .thenReturn(List.of(CmsMenuItemTranslation.create(item.getId(), "ar", "الرئيسية")));

        assertThat(service.findMenuItemById(admin, item.getId())).isPresent();
        assertThat(service.listMenuItems(admin, menuId)).hasSize(1);
    }

    @Test
    void createMenuDerivesTheSlugAndRefusesConflicts() {
        when(menuRepository.existsBySlug("main")).thenReturn(true);
        assertThatThrownBy(() -> service.createMenu(admin, "Main", "main"))
                .isInstanceOf(ConflictException.class);

        when(menuRepository.existsBySlug("fresh")).thenReturn(false);
        CmsMenu created = CmsMenu.create("Fresh", "fresh");
        when(menuRepository.save(any(CmsMenu.class))).thenReturn(created);
        assertThat(service.createMenu(admin, "Fresh", null).slug()).isEqualTo("fresh");
    }

    @Test
    void updateMenuMovesTheSlugWithConflictDetection() {
        UUID id = UUID.randomUUID();
        CmsMenu menu = CmsMenu.create("Main", "main");
        when(menuRepository.findById(id)).thenReturn(Optional.of(menu));
        when(menuRepository.existsBySlug("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.updateMenu(admin, id, null, "taken"))
                .isInstanceOf(ConflictException.class);

        service.updateMenu(admin, id, "Renamed", "renamed");
        assertThat(menu.getName()).isEqualTo("Renamed");
    }

    @Test
    void deleteMenuDetachesTheSlotsAndClearsTheItems() {
        UUID id = UUID.randomUUID();
        CmsMenu menu = CmsMenu.create("Main", "main");
        // The 2026-11-08 #810 round-5 review: the locked menu load.
        when(menuRepository.findByIdForUpdate(id)).thenReturn(Optional.of(menu));
        CmsMenuItem item = root("Home", 0);
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(id)).thenReturn(List.of(item));
        when(itemTranslationRepository.findAllByMenuItemId(item.getId())).thenReturn(List.of());
        CmsNavigationSlot slot = CmsNavigationSlot.create("MAIN", id);
        when(slotRepository.findBySlot("MAIN")).thenReturn(Optional.of(slot));
        when(slotRepository.findBySlot("SECONDARY")).thenReturn(Optional.empty());

        service.deleteMenu(admin, id);

        assertThat(slot.getMenuId()).isNull();
        verify(itemRepository).delete(item);
        verify(menuRepository).delete(menu);
    }

    @Test
    void createMenuItemAppendsTheChildUnderAValidParent() {
        CmsMenuItem parent = root("Parent", 0);
        when(menuRepository.findByIdForUpdate(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));
        when(itemRepository.findAllByMenuIdAndParentIdOrderByPositionAscIdAsc(menuId,
                parent.getId())).thenReturn(List.of());

        var view = service.createMenuItem(admin, menuId, parent.getId(), "Kid",
                "https://kid.example.com", null, null);

        assertThat(view.name()).isEqualTo("Kid");
        assertThat(view.position()).isZero();
    }

    @Test
    void createMenuItemRefusesACrossMenuParent() {
        when(menuRepository.findByIdForUpdate(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        UUID foreignParent = UUID.randomUUID();
        CmsMenuItem parent = CmsMenuItem.create(UUID.randomUUID(), null, "Foreign");
        when(itemRepository.findByIdForUpdate(foreignParent)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.createMenuItem(admin, menuId, foreignParent, "Kid",
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another menu");
    }

    @Test
    void translateMenuItemCreatesAndUpdatesTheTranslation() {
        CmsMenuItem item = root("Docs", 0);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        CmsMenuItemTranslation standing = CmsMenuItemTranslation.create(item.getId(), "ar", "وثائق");
        when(itemTranslationRepository.findByMenuItemIdAndLanguageCode(item.getId(), "ar"))
                .thenReturn(Optional.of(standing));

        service.translateMenuItem(admin, item.getId(), "ar", "الوثائق المحدثة");

        assertThat(standing.getName()).isEqualTo("الوثائق المحدثة");
    }

    @Test
    void assignNavigationUpsertsTheSlotAndValidatesTheMenu() {
        assertThatThrownBy(() -> service.assignNavigation(admin, "SIDEBAR", null))
                .isInstanceOf(IllegalArgumentException.class);

        UUID unknown = UUID.randomUUID();
        when(menuRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assignNavigation(admin, "MAIN", unknown))
                .isInstanceOf(ResourceNotFoundException.class);

        CmsNavigationSlot slot = CmsNavigationSlot.create("MAIN", null);
        when(slotRepository.findBySlot("MAIN")).thenReturn(Optional.of(slot));
        UUID target = UUID.randomUUID();
        when(menuRepository.findByIdForUpdate(target))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));

        service.assignNavigation(admin, "main", target);

        assertThat(slot.getMenuId()).isEqualTo(target);
    }

    @Test
    void updateMenuItemRefusesAMultiTargetInput() {
        UUID id = UUID.randomUUID();
        CmsMenuItem row = root("Docs", 0);
        when(itemRepository.findById(id)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.updateMenuItem(admin, id, null,
                "https://x.example.com", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most one");
    }

    @Test
    void moveMenuItemsRefusesACrossMenuParent() {
        CmsMenuItem item = root("Home", 0);
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        UUID foreignParent = UUID.randomUUID();
        CmsMenuItem parent = CmsMenuItem.create(UUID.randomUUID(), null, "Foreign");
        when(itemRepository.findById(foreignParent)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.moveMenuItems(admin, menuId,
                List.of(item.getId()), List.of(foreignParent), List.of(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to another menu");
    }

    @Test
    void moveMenuItemsRefusesASelfParent() {
        CmsMenuItem item = root("Home", 0);
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.moveMenuItems(admin, menuId,
                List.of(item.getId()), List.of(item.getId()), List.of(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own parent");
    }

    @Test
    void moveMenuItemsRefusesACrossMenuItem() {
        UUID other = UUID.randomUUID();
        CmsMenuItem foreign = CmsMenuItem.create(UUID.randomUUID(), null, "Foreign");
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(other)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.moveMenuItems(admin, menuId, List.of(other),
                java.util.Arrays.asList((UUID) null), List.of(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to another menu");
    }

    @Test
    void theMutationLegsValidateTheNamesAndSlugs() {
        assertThatThrownBy(() -> service.createMenu(admin, " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
        when(menuRepository.findById(menuId)).thenReturn(Optional.of(CmsMenu.create("M", "m")));
        assertThatThrownBy(() -> service.updateMenu(admin, menuId, null, "Not Kebab"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kebab-case");
        assertThatThrownBy(() -> service.translateMenuItem(admin, UUID.randomUUID(), "BAD", "x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
    // ==== The 2026-11-08 #810 round-5 review additions ====

    /**
     * The round-5 overflow nitpick: a move from a POSITIVE index with a
     * near-MAX delta — the sum overflows int to a negative the old clamp
     * read as 0 (a forward move answering a jump to the START). Three
     * siblings; the MIDDLE one moves with Integer.MAX_VALUE and must land
     * at the END.
     */
    @Test
    void moveMenuItemsClampsAPositiveIndexOverflowToTheEnd() {
        CmsMenuItem a = root("A", 0);
        CmsMenuItem b = root("B", 1);
        CmsMenuItem c = root("C", 2);
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(b.getId())).thenReturn(Optional.of(b));
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(a, b, c));

        service.moveMenuItems(admin, menuId, List.of(b.getId()),
                java.util.Arrays.asList((UUID) null), List.of(Integer.MAX_VALUE));

        assertThat(a.getPosition()).isZero();
        assertThat(c.getPosition()).isEqualTo(1);
        assertThat(b.getPosition()).isEqualTo(2);
    }

    /**
     * The round-5 ordering fix (#18): the doomed row dies (and FLUSHES)
     * BEFORE any child promotes — the delete, the flush and the promotion
     * pass ride that exact order, so the V283 unique index never sees a
     * promoted root name while its previous holder still lives.
     */
    @Test
    void deleteMenuItemFlushesTheDoomedRowBeforeThePromotionPass() {
        CmsMenuItem parent = root("Home", 0);
        CmsMenuItem kid = child(parent, "Home", 0);
        when(itemRepository.findByIdForUpdate(parent.getId()))
                .thenReturn(Optional.of(parent));
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(parent, kid));

        service.deleteMenuItem(admin, parent.getId());

        assertThat(kid.getParentId()).isNull();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(itemRepository);
        order.verify(itemRepository).delete(parent);
        order.verify(itemRepository).flush();
    }

    /**
     * The round-5 final-state fix (#19): the roots check runs BEFORE any
     * reParent mutates a managed row — the classified ConflictException
     * answers even when the would-be promotion collides with a SURVIVING
     * root (the query-time AUTO FLUSH used to push the dirty promotion
     * into V283's raw unique violation first).
     */
    @Test
    void moveMenuItemsRefusesARootJoinerBeforeMutatingAnything() {
        CmsMenuItem parent = root("Parent", 0);
        CmsMenuItem item = child(parent, "Home", 0);
        CmsMenuItem stayingHomeRoot = root("Home", 1);
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(itemRepository.findAllByMenuIdAndParentIdIsNullOrderByPositionAscIdAsc(menuId))
                .thenReturn(List.of(parent, stayingHomeRoot));

        assertThatThrownBy(() -> service.moveMenuItems(admin, menuId,
                List.of(item.getId()), java.util.Arrays.asList((UUID) null), List.of(0)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("root menu item with this name");

        // The refusal precedes ANY mutation — the item never moved.
        assertThat(item.getParentId()).isEqualTo(parent.getId());
    }

    /**
     * The round-5 boundary (#5-family): the menu slug's own VARCHAR(255)
     * bound — an oversized slug answers the classified validation error,
     * never the flush's raw storage error.
     */
    @Test
    void createMenuRefusesAnOverlongSlug() {
        String overlong = "s".repeat(256);

        assertThatThrownBy(() -> service.createMenu(admin, "Main", overlong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("255");
        verify(menuRepository, never()).save(any(CmsMenu.class));
    }

}
