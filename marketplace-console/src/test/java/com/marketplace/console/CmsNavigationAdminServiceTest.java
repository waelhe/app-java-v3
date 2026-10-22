package com.marketplace.console;

import com.marketplace.shared.api.CmsNavigationPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Agent 1: the navigation use case's target and ordering invariants. */
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

    private CmsNavigationAdminService service() {
        return new CmsNavigationAdminService(menuRepository, itemRepository,
                itemTranslationRepository, slotRepository);
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void updateMenuItemReplacesAnExistingCategoryWhenAUrlIsSet() {
        UUID menuId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        CmsMenuItem item = CmsMenuItem.create(menuId, null, "Home");
        item.apply(null, null, categoryId, null);
        when(itemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        service().updateMenuItem(admin(), item.getId(), null, " /sale ", null, null);

        assertThat(item.getUrl()).isEqualTo("/sale");
        assertThat(item.getCategoryId()).isNull();
        assertThat(item.getPageId()).isNull();
    }

    @Test
    void relativeMoveUsesTheSiblingOrderInsteadOfUuidTieBreaking() {
        UUID menuId = UUID.randomUUID();
        CmsMenu menu = CmsMenu.create("Main", "main");
        CmsMenuItem first = item(menuId, "First", 0, 1);
        CmsMenuItem second = item(menuId, "Second", 1, 2);
        CmsMenuItem third = item(menuId, "Third", 2, 3);
        List<CmsMenuItem> siblings = new ArrayList<>(List.of(first, second, third));
        when(menuRepository.findById(menuId)).thenReturn(Optional.of(menu));
        when(itemRepository.findById(third.getId())).thenReturn(Optional.of(third));
        when(itemRepository.findAllByMenuIdOrderByPositionAscIdAsc(menuId))
                .thenAnswer(invocation -> new ArrayList<>(siblings));

        service().moveMenuItems(admin(), menuId, List.of(third.getId()),
                java.util.Collections.singletonList(null), List.of(-2));

        assertThat(third.getPosition()).isZero();
        assertThat(first.getPosition()).isEqualTo(1);
        assertThat(second.getPosition()).isEqualTo(2);
    }

    @Test
    void scrollMenuItemsDelegatesTheKeysetAndPreservesWindowPagination() {
        UUID menuId = UUID.randomUUID();
        CmsMenuItem item = item(menuId, "Home", 0, 4);
        KeysetScrollPosition position = ScrollPosition.forward(
                Map.of("name", "Home", "id", item.getId()));
        Limit limit = Limit.of(2);
        Window<CmsMenuItem> window = Window.from(List.of(item), index -> position, true);
        when(itemRepository.findAllByOrderByNameAscIdAsc(position, limit)).thenReturn(window);
        when(itemRepository.count()).thenReturn(5L);

        CmsNavigationPort.MenuItemWindow result = service().scrollMenuItems(
                admin(), "NAME", true, position, limit);

        assertThat(result.window().hasNext()).isTrue();
        assertThat(result.window().getContent())
                .extracting(CmsNavigationPort.MenuItemView::id)
                .containsExactly(item.getId());
        assertThat(result.total()).isEqualTo(5L);
    }

    private static CmsMenuItem item(UUID menuId, String name, int position, long idSuffix) {
        CmsMenuItem item = CmsMenuItem.create(menuId, null, name);
        item.moveTo(position);
        ReflectionTestUtils.setField(item, "id",
                new UUID(0L, idSuffix));
        return item;
    }
}
