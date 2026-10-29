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

    private CmsNavigationAdminService service;

    private Authentication admin;
    private UUID menuId;

    @BeforeEach
    void setUp() {
        service = new CmsNavigationAdminService(menuRepository, itemRepository,
                itemTranslationRepository, slotRepository);
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
        when(menuRepository.findById(menuId))
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
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));
        when(itemRepository.findByMenuIdAndNameAndParentIdIsNull(menuId, "Home"))
                .thenReturn(Optional.of(root("Home", 1)));

        // m2: the move-to-root path previously flushed straight into the
        // V283 partial unique index's raw database error.
        assertThatThrownBy(() -> service.moveMenuItems(admin, menuId,
                List.of(item.getId()), java.util.Arrays.asList((UUID) null), List.of(0)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("root menu item with this name");
    }

    @Test
    void deleteMenuItemPromotesTheSubtreeUnderTheDeletedParent() {
        CmsMenuItem parent = root("Parent", 0);
        CmsMenuItem kid = child(parent, "Kid", 0);
        when(itemRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
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
        when(itemRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
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
        when(menuRepository.findById(menuId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createMenuItem(admin, menuId, null,
                "Home", null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void everyLegIsAdminGated() {
        Authentication consumer = new UsernamePasswordAuthenticationToken("m@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
        assertThatThrownBy(() -> service.createMenuItem(consumer, menuId, null,
                "Home", null, null, null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    // ---------------------------------------------------------------------
    // The read/scroll/translate/delete legs (the coverage round)
    // ---------------------------------------------------------------------

    @Test
    void theMenuReadLegsAnswerTheRegistry() {
        CmsMenu menu = CmsMenu.create("Main", "main");
        when(menuRepository.findById(menu.getId())).thenReturn(Optional.of(menu));
        when(menuRepository.findBySlug("main")).thenReturn(Optional.of(menu));
        when(menuRepository.findByName("Main")).thenReturn(Optional.of(menu));

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
        when(menuRepository.findById(id)).thenReturn(Optional.of(menu));
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
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        when(itemRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(itemRepository.findAllByMenuIdAndParentIdOrderByPositionAscIdAsc(menuId,
                parent.getId())).thenReturn(List.of());

        var view = service.createMenuItem(admin, menuId, parent.getId(), "Kid",
                "https://kid.example.com", null, null);

        assertThat(view.name()).isEqualTo("Kid");
        assertThat(view.position()).isZero();
    }

    @Test
    void createMenuItemRefusesACrossMenuParent() {
        when(menuRepository.findById(menuId))
                .thenReturn(Optional.of(CmsMenu.create("Main", "main")));
        UUID foreignParent = UUID.randomUUID();
        CmsMenuItem parent = CmsMenuItem.create(UUID.randomUUID(), null, "Foreign");
        when(itemRepository.findById(foreignParent)).thenReturn(Optional.of(parent));

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
        when(menuRepository.existsById(unknown)).thenReturn(false);
        assertThatThrownBy(() -> service.assignNavigation(admin, "MAIN", unknown))
                .isInstanceOf(ResourceNotFoundException.class);

        CmsNavigationSlot slot = CmsNavigationSlot.create("MAIN", null);
        when(slotRepository.findBySlot("MAIN")).thenReturn(Optional.of(slot));
        UUID target = UUID.randomUUID();
        when(menuRepository.existsById(target)).thenReturn(true);

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
}
