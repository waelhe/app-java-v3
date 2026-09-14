package com.marketplace.search;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.SavedSearchMatchedEvent;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): the
 * service's gates and the scan's contract in isolation — the save-time
 * type gate (criterion 5), the muted search (criterion 6), the owner
 * scoping, the structural aggregation (criterion 4), the idempotency
 * skip (criterion 3-b) and the no-match silence (criterion 2).
 */
@ExtendWith(MockitoExtension.class)
class SavedSearchServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();
    private static final UUID LISTING = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private SavedSearchRepository repository;

    @Mock
    private SavedSearchMatcher matcher;

    @Mock
    private GeoLookupPort geoLookupPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private JdbcTemplate jdbcTemplate;


    private SavedSearchService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new SavedSearchService(repository, matcher, geoLookupPort, eventPublisher,
                jdbcTemplate, mapper, Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), java.time.ZoneOffset.UTC));
    }

    private static SavedSearch searchOf(UUID userId, boolean alertEnabled) {
        return SavedSearch.create(UUID.randomUUID(), userId,
                new SearchCriteria(null, "stay", null, null), alertEnabled);
    }

    // ------------------------------------------------------------------
    // The save-time gates (criterion 5 + the location 404)
    // ------------------------------------------------------------------

    @Test
    void create_invalidWindow_answers400BeforeAnyWrite() {
        var node = mapper.readTree("{\"checkIn\":\"2026-10-04T10:00:00Z\",\"checkOut\":\"2026-10-01T14:00:00Z\"}");
        assertThatThrownBy(() -> service.create(USER, node, true))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any(SavedSearch.class));
    }

    @Test
    void create_unknownLocation_answers404LikeTheSearchSurface() {
        when(geoLookupPort.findSelfAndDescendants(LOCATION)).thenReturn(Set.of());
        var node = mapper.readTree("{\"locationId\":\"" + LOCATION + "\"}");
        assertThatThrownBy(() -> service.create(USER, node, true))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any(SavedSearch.class));
    }

    @Test
    void create_unknownEnumName_answers400() {
        var node = mapper.readTree("{\"purpose\":\"NOT_A_PURPOSE\"}");
        assertThatThrownBy(() -> service.create(USER, node, true))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid search criteria");
    }

    @Test
    void create_mutedSearch_isStoredForLaterReUseOnly() {
        when(repository.save(any(SavedSearch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(geoLookupPort.findSelfAndDescendants(LOCATION)).thenReturn(Set.of(LOCATION));
        var node = mapper.readTree("{\"locationId\":\"" + LOCATION + "\",\"minRooms\":2}");
        SavedSearch stored = service.create(USER, node, false);

        assertThat(stored.isAlertEnabled()).isFalse();
        assertThat(stored.getCriteria().minRooms()).isEqualTo(2);
        verify(repository).save(any(SavedSearch.class));
    }

    @Test
    void delete_foreignSavedSearch_isAnHonest404() {
        SavedSearch foreign = searchOf(OTHER_USER, true);
        when(repository.findById(foreign.getId())).thenReturn(java.util.Optional.of(foreign));

        assertThatThrownBy(() -> service.delete(USER, foreign.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(any(SavedSearch.class));
    }

    // ------------------------------------------------------------------
    // The scan (criteria 1-4, 3-b)
    // ------------------------------------------------------------------

    @Test
    void scan_matchingSearch_publishesOneAggregatedEventPerUser_andStampsTheSearch() {
        SavedSearch a = searchOf(USER, true);
        SavedSearch b = searchOf(USER, true);
        SavedSearch other = searchOf(OTHER_USER, true);
        when(repository.findAllAlertEnabled(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a, b, other)));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(true);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(1);

        service.processListingActivated(LISTING, PROVIDER);

        // ONE event per user — the structural aggregation (criterion 4)
        ArgumentCaptor<SavedSearchMatchedEvent> captor =
                ArgumentCaptor.forClass(SavedSearchMatchedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        var userEvents = captor.getAllValues().stream()
                .filter(e -> e.userId().equals(USER)).toList();
        assertThat(userEvents).hasSize(1);
        assertThat(userEvents.get(0).savedSearchIds()).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(captor.getAllValues().stream()
                .filter(e -> e.userId().equals(OTHER_USER)).toList()).hasSize(1);
        // the bookkeeping stamp
        assertThat(a.getLastMatchedAt()).isNotNull();
    }

    @Test
    void scan_noMatch_publishesNothing() {
        SavedSearch a = searchOf(USER, true);
        when(repository.findAllAlertEnabled(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(false);

        service.processListingActivated(LISTING, PROVIDER);

        verify(eventPublisher, never()).publishEvent(any(SavedSearchMatchedEvent.class));
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
        assertThat(a.getLastMatchedAt()).isNull();
    }

    @Test
    void scan_alreadyReportedPair_isTheDocumentedSkip_noSecondEvent() {
        SavedSearch a = searchOf(USER, true);
        when(repository.findAllAlertEnabled(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(true);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(0); // ON CONFLICT DO NOTHING — already reported

        service.processListingActivated(LISTING, PROVIDER);

        verify(eventPublisher, never()).publishEvent(any(SavedSearchMatchedEvent.class));
        assertThat(a.getLastMatchedAt()).isNull();
    }

    @Test
    void scan_pagesThroughTheAlertEnabledSet() {
        SavedSearch a = searchOf(USER, true);
        when(repository.findAllAlertEnabled(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a), PageRequest.of(0, 200), 201))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 200), 201));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(false);

        service.processListingActivated(LISTING, PROVIDER);

        verify(repository, org.mockito.Mockito.times(2)).findAllAlertEnabled(any(Pageable.class));
    }
}
