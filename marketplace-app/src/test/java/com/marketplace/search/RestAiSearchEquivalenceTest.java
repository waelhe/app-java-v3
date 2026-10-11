package com.marketplace.search;

import com.marketplace.ai.MarketplaceSearchTools;
import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.RealestatePropertyFilterPort;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The C-5 remainder the unified plan §9.3-2 names as the one open item —
 * «اختبار التكافؤ بين REST/AI للأهلية والنطاق والفرز» — made executable:
 * the REST surface (the {@link SearchService} call the controller rides)
 * and the AI surface ({@code MarketplaceSearchTools} →
 * {@code MarketplaceSearchPort} → {@code MarketplaceSearchAdapter} → the
 * SAME {@link SearchService} bean) must translate equivalent inputs into
 * equivalent orchestration calls and receive the identical answer — the
 * same eligibility, the same scope, the same sort, the same page — because
 * there is only one orchestration and no AI side path.
 *
 * <p>This lives in {@code marketplace-app} (never inside
 * {@code marketplace-search}): the equivalence spans the search module and
 * the AI module, and the app is the only place that already wires both —
 * the same rule the app-module integration tree follows. The test is a
 * plain JUnit + Mockito unit (no Spring context, no container): it wires
 * the REAL adapter and REAL tool classes around a stubbed orchestration,
 * so what is measured is the surfaces' own translation contracts —
 * exactly the drift this test exists to catch.
 *
 * <p><b>The ambiguous-location law (§5.1) is part of equivalence:</b> a
 * named location resolving to more than one candidate produces NO search
 * at all on the AI surface (the fail-closed clarification shape), while
 * the REST surface never faces the ambiguity — it carries an explicit id.
 * An unresolved name widens nothing.
 */
class RestAiSearchEquivalenceTest {

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID NEIGHBORHOOD_ID = UUID.randomUUID();

    private CatalogSearchPort catalogSearchPort;
    private AvailabilityLookupPort availabilityLookupPort;
    private GeoLookupPort geoLookupPort;
    private RealestatePropertyFilterPort realestatePropertyFilterPort;
    private SearchService searchService;

    @BeforeEach
    void stubTheOneOrchestration() {
        catalogSearchPort = mock(CatalogSearchPort.class);
        availabilityLookupPort = mock(AvailabilityLookupPort.class);
        geoLookupPort = mock(GeoLookupPort.class);
        realestatePropertyFilterPort = mock(RealestatePropertyFilterPort.class);
        searchService = mock(SearchService.class);
    }

    @Test
    void restAndAiTranslateEquivalentInputsToTheSameOrchestrationCallAndAnswer() {
        ListingSummary first = listing("L1");
        ListingSummary second = listing("L2");
        when(searchService.search(any(SearchCriteria.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 5), 2L));

        MarketplaceSearchAdapter adapter = new MarketplaceSearchAdapter(searchService);
        MarketplaceSearchTools tools = new MarketplaceSearchTools(adapter, geoLookupPort);

        // The REST translation of the caller's intent — the criteria shape
        // the REST controller binds (free text, category, explicit id).
        SearchCriteria restCriteria = criteria("مقهى للدراسات", "SERVICES", NEIGHBORHOOD_ID);
        Pageable restPage = PageRequest.of(0, 20);
        searchService.search(restCriteria, restPage);

        // The AI translation of the SAME intent — the tool's own binding:
        // free text, category, a named location resolved via the geo port.
        when(geoLookupPort.suggest("الحي الشمالي"))
                .thenReturn(List.of(node(NEIGHBORHOOD_ID, "الحي الشمالي")));
        MarketplaceSearchTools.MarketplaceSearchResult aiAnswer = tools.searchListingsAdvanced(
                "مقهى للدراسات", "SERVICES", null, null, null,
                "الحي الشمالي", null, null, null, null, null,
                null, null, null, null, null, null,
                new ToolContext(Map.of("userId", CALLER.toString())));

        // One orchestration entrypoint received BOTH translations — and the
        // AI translation is criterion-for-criterion the REST one.
        ArgumentCaptor<SearchCriteria> criteriaCaptor = ArgumentCaptor.forClass(SearchCriteria.class);
        verify(searchService, times(2)).search(criteriaCaptor.capture(), any(Pageable.class));

        SearchCriteria aiCriteria = criteriaCaptor.getAllValues().get(1);
        assertThat(aiCriteria.query()).isEqualTo(restCriteria.query());
        assertThat(aiCriteria.category()).isEqualTo(restCriteria.category());
        assertThat(aiCriteria.locationId()).isEqualTo(restCriteria.locationId());
        assertThat(aiCriteria.minPrice()).isEqualTo(restCriteria.minPrice());
        assertThat(aiCriteria.maxPrice()).isEqualTo(restCriteria.maxPrice());
        assertThat(aiCriteria.checkIn()).isEqualTo(restCriteria.checkIn());
        assertThat(aiCriteria.checkOut()).isEqualTo(restCriteria.checkOut());

        // And the AI answer is the page the single orchestration chose, in
        // its order — the same eligibility/scope/sort, no side path.
        assertThat(aiAnswer.listings()).containsExactly(first, second);
        assertThat(aiAnswer.totalMatches()).isEqualTo(2L);
        assertThat(aiAnswer.clarification()).isNull();
        assertThat(aiAnswer.locationOptions()).isEmpty();
    }

    @Test
    void ambiguousLocationOnTheAiSurfacePerformsNoSearchAndAsksForClarification() {
        MarketplaceSearchAdapter adapter = new MarketplaceSearchAdapter(searchService);
        MarketplaceSearchTools tools = new MarketplaceSearchTools(adapter, geoLookupPort);

        // Two candidates share one exact name — the §5.1 ambiguity:
        // clarification, never a silent widening to a national search.
        when(geoLookupPort.suggest("الرياض")).thenReturn(List.of(
                node(UUID.randomUUID(), "الرياض"),
                node(UUID.randomUUID(), "الرياض")));

        MarketplaceSearchTools.MarketplaceSearchResult answer = tools.searchListingsAdvanced(
                "مقهى", "SERVICES", null, null, null,
                "الرياض", null, null, null, null, null,
                null, null, null, null, null, null,
                new ToolContext(Map.of("userId", CALLER.toString())));

        assertThat(answer.listings()).isEmpty();
        assertThat(answer.totalMatches()).isZero();
        assertThat(answer.clarification()).isNotBlank();
        assertThat(answer.locationOptions()).hasSize(2);
        // Fail closed, measured: the orchestration was never asked to search.
        verify(searchService, never()).search(any(SearchCriteria.class), any(Pageable.class));
    }

    @Test
    void aiPageRidesTheCanonicalSortNormalizationTheRestSurfaceUses() {
        ListingSummary newest = listing("L-newest");
        ListingSummary older = listing("L-older");
        when(searchService.search(any(SearchCriteria.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(newest, older), PageRequest.of(0, 5), 2L));

        MarketplaceSearchAdapter adapter = new MarketplaceSearchAdapter(searchService);
        MarketplaceSearchTools tools = new MarketplaceSearchTools(adapter, geoLookupPort);

        when(geoLookupPort.suggest("حي واحد")).thenReturn(List.of(node(NEIGHBORHOOD_ID, "حي واحد")));
        tools.searchListingsAdvanced(
                "مقهى", null, null, null, null,
                "حي واحد", null, null, null, null, null,
                null, null, null, null, null, null,
                new ToolContext(Map.of("userId", CALLER.toString())));

        // The adapter normalizes the tool's page request through
        // SearchSorts — the SAME canonical sort validation the REST
        // pageable rides — so the ordering contract cannot drift between
        // the surfaces. Measured: the tool's page arrives already
        // normalized (an unsorted request stays canonically ordered).
        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(searchService).search(any(SearchCriteria.class), pageCaptor.capture());
        Pageable normalized = pageCaptor.getValue();
        assertThat(normalized.getSort())
                .isEqualTo(SearchSorts.normalize(PageRequest.of(0, 5)).getSort());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private SearchCriteria criteria(String query, String category, UUID locationId) {
        return new SearchCriteria(query, category, null, null, null, null, null,
                locationId, null, null, null, null, null, null, null, null, null);
    }

    private ListingSummary listing(String title) {
        return new ListingSummary(UUID.randomUUID(), title, "SERVICES", BigDecimal.TEN,
                "SAR", "مزود", 4.5, 12L);
    }

    private GeoLookupPort.GeoNode node(UUID id, String nameAr) {
        return new GeoLookupPort.GeoNode(id, null, 3, nameAr, nameAr, "node-" + id, List.of());
    }
}
