package com.marketplace.app.graphql;

import com.marketplace.shared.api.CmsNavigationPort;
import com.marketplace.shared.api.CmsPagePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.CursorEncoder;
import org.springframework.graphql.data.pagination.CursorStrategy;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.graphql.data.query.ScrollPositionCursorStrategy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Agent 1: the CMS connections' cursor gate contracts. */
@ExtendWith(MockitoExtension.class)
class CmsCursorDataFetcherTest {

    private static final UUID TIE_BREAKER = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Mock
    private CmsNavigationPort navigationPort;

    @Mock
    private CmsPagePort pagePort;

    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy =
            CursorStrategy.withEncoder(new ScrollPositionCursorStrategy(), CursorEncoder.base64());

    private CmsNavigationDataFetcher navigationFetcher;
    private CmsPageDataFetcher pageFetcher;

    @BeforeEach
    void setUp() {
        GraphQlProperties properties = new GraphQlProperties(null,
                new GraphQlProperties.Pagination(20, 100), null);
        SaleorGlobalIdCodec codec = new SaleorGlobalIdCodec();
        navigationFetcher = new CmsNavigationDataFetcher(navigationPort, codec, cursorStrategy,
                properties);
        pageFetcher = new CmsPageDataFetcher(pagePort, null, null, codec, cursorStrategy,
                properties);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void menusForwardsTheDecodedNameKeysetInsteadOfTheStartPosition() {
        when(navigationPort.scrollMenus(any(), eq("NAME"), eq(true), any(), any()))
                .thenReturn(new CmsNavigationPort.MenuWindow(
                        Window.from(List.<CmsNavigationPort.MenuView>of(),
                                index -> ScrollPosition.keyset(), false), 0));

        navigationFetcher.menus(2, cursor("name", "Main"), null, null, null, null, null);

        ArgumentCaptor<KeysetScrollPosition> position =
                ArgumentCaptor.forClass(KeysetScrollPosition.class);
        verify(navigationPort).scrollMenus(any(), eq("NAME"), eq(true), position.capture(), any());
        assertThat(position.getValue().getKeys())
                .containsExactlyInAnyOrderEntriesOf(Map.of("name", "Main", "id", TIE_BREAKER));
    }

    @Test
    void menuItemsForwardsTheDecodedNameKeysetInsteadOfTheStartPosition() {
        when(navigationPort.scrollMenuItems(any(), eq("NAME"), eq(true), any(), any()))
                .thenReturn(new CmsNavigationPort.MenuItemWindow(
                        Window.from(List.<CmsNavigationPort.MenuItemView>of(),
                                index -> ScrollPosition.keyset(), false), 0));

        navigationFetcher.menuItems(2, cursor("name", "Home"), null, null, null, null, null);

        ArgumentCaptor<KeysetScrollPosition> position =
                ArgumentCaptor.forClass(KeysetScrollPosition.class);
        verify(navigationPort).scrollMenuItems(any(), eq("NAME"), eq(true), position.capture(),
                any());
        assertThat(position.getValue().getKeys())
                .containsExactlyInAnyOrderEntriesOf(Map.of("name", "Home", "id", TIE_BREAKER));
    }

    @Test
    void pageTypesForwardsTheDecodedNameKeysetInsteadOfTheSlugPosition() {
        when(pagePort.scrollPageTypes(any(), eq("NAME"), eq(true), any(), any()))
                .thenReturn(new CmsPagePort.PageTypeWindow(
                        Window.from(List.<CmsPagePort.PageTypeView>of(),
                                index -> ScrollPosition.keyset(), false), 0));

        pageFetcher.pageTypes(2, cursor("name", "Landing"), null, null, null, null);

        ArgumentCaptor<KeysetScrollPosition> position =
                ArgumentCaptor.forClass(KeysetScrollPosition.class);
        verify(pagePort).scrollPageTypes(any(), eq("NAME"), eq(true), position.capture(), any());
        assertThat(position.getValue().getKeys())
                .containsExactlyInAnyOrderEntriesOf(Map.of("name", "Landing", "id", TIE_BREAKER));
    }

    private String cursor(String property, String value) {
        return cursorStrategy.toCursor(ScrollPosition.forward(
                Map.of(property, value, "id", TIE_BREAKER)));
    }
}
