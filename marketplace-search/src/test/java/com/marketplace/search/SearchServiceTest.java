package com.marketplace.search;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchServiceTest {

    private final CatalogSearchPort port = mock(CatalogSearchPort.class);
    private final AvailabilityLookupPort availabilityPort = mock(AvailabilityLookupPort.class);
    private final SearchService service = new SearchService(port, availabilityPort);

    private static Page<ListingSummary> emptyPage() {
        return new PageImpl<>(List.of());
    }

    // ---- L27: the window path -------------------------------------------------

    private static final Instant CHECK_IN = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant CHECK_OUT = CHECK_IN.plusSeconds(3 * 24 * 3600);

    @Test
    void windowWithoutQuery_restrictsTheCriteriaSearchToAvailableProviders() {
        UUID available = UUID.randomUUID();
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of(available));
        when(port.searchByCriteriaRestricted(any(), any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT), PageRequest.of(0, 10));

        verify(availabilityPort).findAvailableProviderIds(CHECK_IN, CHECK_OUT);
        // The whitelist rides the restricted criteria query — the criteria
        // record passes through as-is (the catalog contract reads only its
        // category/price components, the window itself is already resolved
        // into the whitelist), and the unrestricted branches are never taken.
        verify(port).searchByCriteriaRestricted(argThat(SearchCriteria::hasWindow), eq(Set.of(available)), eq(PageRequest.of(0, 10)));
        verify(port, never()).listActive(any());
        verify(port, never()).searchByCriteria(any(), any());
    }

    @Test
    void windowWithQuery_restrictsTheFullTextSearchToAvailableProviders() {
        UUID available = UUID.randomUUID();
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of(available));
        when(port.searchFullTextRestricted(anyString(), any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria("yoga retreat", null, null, null, CHECK_IN, CHECK_OUT), PageRequest.of(0, 10));

        verify(port).searchFullTextRestricted(eq("yoga retreat"), eq(Set.of(available)), eq(PageRequest.of(0, 10)));
        // The unrestricted FTS branch is never taken when a window is present.
        verify(port, never()).searchFullText(anyString(), any());
    }

    @Test
    void windowWithNoAvailableProvider_isAnHonestEmptyPageWithoutAnyCatalogQuery() {
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of());

        Page<ListingSummary> page = service.search(new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT), PageRequest.of(0, 10));

        assertThat(page).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(port, never()).searchByCriteriaRestricted(any(), any(), any());
        verify(port, never()).searchFullTextRestricted(anyString(), any(), any());
        verify(port, never()).listActive(any());
    }

    // ---- The pre-L27 dispatch: unchanged (backward compatibility) --------------

    @Test
    void usesCriteriaSearchWhenPriceFilterProvided() {
        when(port.searchByCriteria(any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, BigDecimal.valueOf(10), null), PageRequest.of(0, 20));

        verify(port).searchByCriteria(any(), any());
        verify(port, never()).listActive(any());
    }

    // ---- I6: the guests dispatch ------------------------------------------------

    @Test
    void guestsOnlyCriterion_routesToTheCriteriaQuery_notListActive() {
        // The dispatch bug this change guards against: a guests-only
        // criterion (no query, no price, no category) must reach the
        // criteria query — listActive would silently bypass the capacity
        // filter.
        when(port.searchByCriteria(any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, null, null, null, null, 4), PageRequest.of(0, 10));

        verify(port).searchByCriteria(argThat(c -> c.guests() != null && c.guests().equals(4)), eq(PageRequest.of(0, 10)));
        verify(port, never()).listActive(any());
        verify(port, never()).listByCategory(anyString(), any());
    }

    @Test
    void categoryWithGuests_routesToTheCriteriaQuery_notCategoryBranch() {
        // category+guests composes in ONE criteria query (the category
        // predicate rides the same native query) — the dedicated category
        // branch (which has no capacity predicate) is never taken.
        when(port.searchByCriteria(any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, "stay", null, null, null, null, 2), PageRequest.of(0, 10));

        verify(port).searchByCriteria(argThat(c -> "stay".equals(c.category()) && Integer.valueOf(2).equals(c.guests())), eq(PageRequest.of(0, 10)));
        verify(port, never()).listByCategory(anyString(), any());
    }

    @Test
    void guestsWithWindow_ridesTheRestrictedCriteriaQuery() {
        UUID available = UUID.randomUUID();
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of(available));
        when(port.searchByCriteriaRestricted(any(), any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT, 3), PageRequest.of(0, 10));

        verify(port).searchByCriteriaRestricted(
                argThat(c -> c.hasWindow() && Integer.valueOf(3).equals(c.guests())), eq(Set.of(available)), eq(PageRequest.of(0, 10)));
    }

    @Test
    void usesFullTextSearchWhenQueryProvided() {
        when(port.searchFullText(anyString(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria("hello world", null, null, null), PageRequest.of(0, 10));

        // Raw pass-through (trim only): websearch_to_tsquery owns the parsing.
        verify(port).searchFullText("hello world", PageRequest.of(0, 10));
    }

    @Test
    void passesRawInputThroughUnmangled() {
        when(port.searchFullText(anyString(), any())).thenReturn(emptyPage());

        // Quotes/parens/dashes are valid websearch_to_tsquery syntax, not
        // pre-mangled "&" tsquery operators (the old munging fed to_tsquery
        // invalid syntax -> SQL exception -> HTTP 500).
        service.search(new SearchCriteria("\"garden view\" -crab ((", null, null, null),
                PageRequest.of(0, 10));

        verify(port).searchFullText("\"garden view\" -crab ((", PageRequest.of(0, 10));
    }

    @Test
    void usesCategoryWhenNoQueryOrPrice() {
        when(port.listByCategory(anyString(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, "tech", null, null), PageRequest.of(0, 10));

        verify(port).listByCategory("tech", PageRequest.of(0, 10));
    }

    @Test
    void usesListActiveWhenNoCriteria() {
        when(port.listActive(any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, null, null), PageRequest.of(0, 10));

        verify(port).listActive(PageRequest.of(0, 10));
    }

    @Test
    void searchByCategory_delegates() {
        when(port.listByCategory(anyString(), any())).thenReturn(emptyPage());

        service.searchByCategory("books", PageRequest.of(0, 5));

        verify(port).listByCategory("books", PageRequest.of(0, 5));
    }

    @Test
    void searchAll_delegates() {
        when(port.listActive(any())).thenReturn(emptyPage());

        service.searchAll(PageRequest.of(0, 20));

        verify(port).listActive(PageRequest.of(0, 20));
    }
}
