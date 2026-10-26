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
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): the
 * service's gates and the scan's contract in isolation — the save-time
 * type gate (criterion 5), the per-user availability bound (the CodeRabbit
 * round-1 adoption), the muted search (criterion 6), the owner scoping,
 * the structural aggregation (criterion 4), the idempotency skip
 * (criterion 3-b), the no-match silence (criterion 2) and the keyset
 * scan's resume contract.
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

    @Mock
    private jakarta.persistence.EntityManager entityManager;

    private SavedSearchService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new SavedSearchService(repository, matcher, geoLookupPort, eventPublisher,
                jdbcTemplate, mapper,
                Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), java.time.ZoneOffset.UTC),
                new SearchProperties(new SearchProperties.SavedSearches(20, Duration.ofHours(1))),
                entityManager);
    }

    /** The fixed clock's now — the claim stamps and budget arithmetic pin to it. */
    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

    /**
     * Row 25: the claim gate's happy path — no scan in flight (the TRY
     * advisory lock answers TRUE) and no prior claim (first activation
     * for the listing). Every scan test starts from here.
     */
    private void givenTheScanClaimIsAvailable() {
        when(jdbcTemplate.queryForList(eq(SavedSearchService.TRY_SCAN_LOCK), eq(Boolean.class), anyString()))
                .thenReturn(List.of(Boolean.TRUE));
        when(jdbcTemplate.queryForList(eq(SavedSearchService.READ_SCAN_CLAIM), eq(Timestamp.class), any()))
                .thenReturn(List.of());
    }

    /** Row 25: the prior claim's stamp — the last ACTUAL scan's instant. */
    private void givenTheLastScanWasAt(Instant lastScanAt) {
        when(jdbcTemplate.queryForList(eq(SavedSearchService.READ_SCAN_CLAIM), eq(Timestamp.class), any()))
                .thenReturn(List.of(Timestamp.from(lastScanAt)));
    }

    private static SavedSearch searchOf(UUID userId, boolean alertEnabled) {
        return SavedSearch.create(UUID.randomUUID(), userId,
                new SearchCriteria(null, "stay", null, null), alertEnabled);
    }

    // ------------------------------------------------------------------
    // The save-time gates (criterion 5 + the location 404 + the cap)
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
    void create_atThePerUserCap_answers409() {
        // The CodeRabbit round-1 adoption: the availability bound — the
        // rate limiter bounds frequency, this bounds the stored set.
        when(repository.countByUserId(USER)).thenReturn(20L);
        var node = mapper.readTree("{}");

        assertThatThrownBy(() -> service.create(USER, node, true))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("Saved-search limit reached");
        verify(repository, never()).save(any(SavedSearch.class));
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
    // The scan (criteria 1-4, 3-b + the keyset contract)
    // ------------------------------------------------------------------

    @Test
    void scan_matchingSearch_publishesOneAggregatedEventPerUser_andStampsTheSearch() {
        givenTheScanClaimIsAvailable();
        SavedSearch a = searchOf(USER, true);
        SavedSearch b = searchOf(USER, true);
        SavedSearch other = searchOf(OTHER_USER, true);
        when(repository.findFirstAlertEnabledBatch(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a, b, other)));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(true);
        // the claim stamp (3-arg form) is a DIFFERENT jdbc call than the
        // ledger insert (5-arg form) — both are stubbed explicitly so
        // strict stubs see the exact shapes the scan issues. The stamp's
        // matchers are TYPED (UUID/Timestamp): untyped any() would let
        // javac bind the stub to JdbcTemplate.update(String, Object[],
        // int[]) — a different overload than the varargs one the service
        // calls — and the stub would silently never match.
        when(jdbcTemplate.update(eq(SavedSearchService.STAMP_SCAN_CLAIM), any(UUID.class), any(Timestamp.class)))
                .thenReturn(1);
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

    /**
     * Row 3 (agent findings 2026-10-26 14:33 export, CWE-755): the
     * per-search failure boundary — one stored criterion the eventual
     * query consumer rejects (however it slipped past the save-time
     * gates) is QUARANTINED for this activation (logged with its
     * identity, skipped) and never aborts the population's notification
     * processing: the healthy saved search still matches, still stamps,
     * still publishes its aggregated event.
     */
    @Test
    void scan_poisonedSearchIsQuarantined_andTheHealthySearchStillAlerts() {
        givenTheScanClaimIsAvailable();
        SavedSearch poisoned = searchOf(USER, true);
        SavedSearch healthy = searchOf(OTHER_USER, true);
        when(repository.findFirstAlertEnabledBatch(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(poisoned, healthy)));
        when(matcher.matches(same(poisoned.getCriteria()), eq(LISTING), eq(PROVIDER)))
                .thenThrow(new com.marketplace.shared.api.BadRequestException(
                        "minPrice is limited to the supported monetary range"));
        when(matcher.matches(same(healthy.getCriteria()), eq(LISTING), eq(PROVIDER)))
                .thenReturn(true);
        when(jdbcTemplate.update(eq(SavedSearchService.STAMP_SCAN_CLAIM), any(UUID.class), any(Timestamp.class)))
                .thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(1);

        service.processListingActivated(LISTING, PROVIDER);

        // The healthy search's alert survived the poisoned neighbor.
        ArgumentCaptor<SavedSearchMatchedEvent> captor =
                ArgumentCaptor.forClass(SavedSearchMatchedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(1)).publishEvent(captor.capture());
        assertThat(captor.getAllValues().get(0).userId()).isEqualTo(OTHER_USER);
        assertThat(captor.getAllValues().get(0).savedSearchIds())
                .containsExactlyInAnyOrder(healthy.getId());
        // The poisoned search was skipped, never stamped.
        assertThat(poisoned.getLastMatchedAt()).isNull();
        assertThat(healthy.getLastMatchedAt()).isNotNull();
    }

    @Test
    void scan_noMatch_publishesNothing() {
        givenTheScanClaimIsAvailable();
        SavedSearch a = searchOf(USER, true);
        when(repository.findFirstAlertEnabledBatch(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(false);

        service.processListingActivated(LISTING, PROVIDER);

        verify(eventPublisher, never()).publishEvent(any(SavedSearchMatchedEvent.class));
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
        assertThat(a.getLastMatchedAt()).isNull();
    }

    @Test
    void scan_alreadyReportedPair_isTheDocumentedSkip_noSecondEvent() {
        givenTheScanClaimIsAvailable();
        SavedSearch a = searchOf(USER, true);
        when(repository.findFirstAlertEnabledBatch(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(true);
        // the claim stamp (3-arg form) — typed matchers, see the
        // aggregation test's note on the overload trap
        when(jdbcTemplate.update(eq(SavedSearchService.STAMP_SCAN_CLAIM), any(UUID.class), any(Timestamp.class)))
                .thenReturn(1);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
                .thenReturn(0); // ON CONFLICT DO NOTHING — already reported

        service.processListingActivated(LISTING, PROVIDER);

        verify(eventPublisher, never()).publishEvent(any(SavedSearchMatchedEvent.class));
        assertThat(a.getLastMatchedAt()).isNull();
    }

    @Test
    void scan_followsTheKeyset_noRowIsVisitedTwice() {
        givenTheScanClaimIsAvailable();
        // The CodeRabbit round-1 adoption: the keyset contract — the next
        // batch resumes strictly after the LAST SEEN id, never after an
        // offset window (a concurrent soft delete cannot shift the keyset).
        // The sequence itself is the assertion: after(a), then after(b),
        // then the terminal empty batch (hasNext=false) ends the loop.
        SavedSearch a = searchOf(USER, true);
        SavedSearch b = searchOf(USER, true);
        when(repository.findFirstAlertEnabledBatch(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a), PageRequest.of(0, 200), 201));
        // PageImpl.hasNext() is derived from totalPages — a [b]-content
        // page with total=2 fits ONE page of 200 and answers hasNext()
        // FALSE (the measured trap behind this test's first failure); the
        // middle batch needs an over-page total (201) to keep the loop
        // walking, and the terminal empty batch (total=0) ends it.
        when(repository.findAlertEnabledAfter(eq(a.getId()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(b), PageRequest.of(0, 200), 201));
        when(repository.findAlertEnabledAfter(eq(b.getId()), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(false);

        service.processListingActivated(LISTING, PROVIDER);

        var afterCaptor = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(repository, org.mockito.Mockito.times(2))
                .findAlertEnabledAfter(afterCaptor.capture(), any(Pageable.class));
        assertThat(afterCaptor.getAllValues())
                .as("the keyset resumes strictly after the last seen id, in order")
                .containsExactly(a.getId(), b.getId());
        // the persistence context is bounded per batch (flush+clear)
        verify(entityManager, org.mockito.Mockito.atLeastOnce()).flush();
        verify(entityManager, org.mockito.Mockito.atLeastOnce()).clear();
    }

    // ------------------------------------------------------------------
    // Row 25 (CWE-400): the coalescing gate — the activation budget
    // ------------------------------------------------------------------

    @Test
    void scan_firstActivation_takesTheClaimAndStampsItWithTheScan() {
        givenTheScanClaimIsAvailable();
        SavedSearch a = searchOf(USER, true);
        when(repository.findFirstAlertEnabledBatch(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(false);

        service.processListingActivated(LISTING, PROVIDER);

        // the claim stamp rides the scan's own transaction (the row-8
        // affected-count discipline: the SQL is pinned by the constant)
        verify(jdbcTemplate).update(eq(SavedSearchService.STAMP_SCAN_CLAIM), eq(LISTING),
                eq(Timestamp.from(NOW)));
        verify(repository).findFirstAlertEnabledBatch(any(Pageable.class));
    }

    @Test
    void scan_secondActivationInsideTheBudget_isCoalescedWithZeroWork() {
        // The finding's exact shape: a provider re-activates a listing
        // whose scan is still inside the activation budget (default 1h) —
        // NO batch, NO matcher call, NO ledger write, NO re-stamp (the
        // budget anchors to the last ACTUAL scan).
        when(jdbcTemplate.queryForList(eq(SavedSearchService.TRY_SCAN_LOCK), eq(Boolean.class), anyString()))
                .thenReturn(List.of(Boolean.TRUE));
        givenTheLastScanWasAt(NOW.minus(Duration.ofMinutes(30)));

        service.processListingActivated(LISTING, PROVIDER);

        verifyNoInteractions(repository, matcher);
        verify(eventPublisher, never()).publishEvent(any(SavedSearchMatchedEvent.class));
        verify(jdbcTemplate, never()).update(eq(SavedSearchService.STAMP_SCAN_CLAIM),
                any(UUID.class), any(Timestamp.class));
        verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.contains("saved_search_matches"),
                any(), any(), any(), any());
    }

    @Test
    void scan_afterTheBudgetPasses_scansAgain() {
        when(jdbcTemplate.queryForList(eq(SavedSearchService.TRY_SCAN_LOCK), eq(Boolean.class), anyString()))
                .thenReturn(List.of(Boolean.TRUE));
        // the last scan is 1h + 1s ago — outside the budget
        givenTheLastScanWasAt(NOW.minus(Duration.ofHours(1)).minus(Duration.ofSeconds(1)));
        SavedSearch a = searchOf(USER, true);
        when(repository.findFirstAlertEnabledBatch(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(false);

        service.processListingActivated(LISTING, PROVIDER);

        verify(repository).findFirstAlertEnabledBatch(any(Pageable.class));
        // the re-claim re-stamps at NOW — the next budget window starts here
        verify(jdbcTemplate).update(eq(SavedSearchService.STAMP_SCAN_CLAIM), eq(LISTING),
                eq(Timestamp.from(NOW)));
    }

    @Test
    void scan_inFlightDuplicate_isCoalescedImmediately_theClaimIsNeverRead() {
        // A scan for this listing is already running — the TRY advisory
        // lock answers FALSE and the claim row is not even read (fail
        // fast, never a parked listener thread).
        when(jdbcTemplate.queryForList(eq(SavedSearchService.TRY_SCAN_LOCK), eq(Boolean.class), anyString()))
                .thenReturn(List.of(Boolean.FALSE));

        service.processListingActivated(LISTING, PROVIDER);

        verify(jdbcTemplate, never())
                .queryForList(eq(SavedSearchService.READ_SCAN_CLAIM), eq(Timestamp.class), any());
        verifyNoInteractions(repository, matcher);
        verify(jdbcTemplate, never()).update(anyString(),
                any(UUID.class), any(Timestamp.class));
    }

    @Test
    void scan_zeroBudget_runsOnEveryActivation_theInFlightGateAloneRemains() {
        // 0s is the documented escape hatch: the time budget is OFF (the
        // pre-row-25 behaviour returns), only the in-flight coalescing
        // still holds. A claim stamped at NOW itself must not block the
        // very next activation (plus(ZERO).isAfter(now) is false at
        // equality — the boundary is inclusive on purpose).
        SavedSearchService unclocked = new SavedSearchService(repository, matcher, geoLookupPort,
                eventPublisher, jdbcTemplate, mapper,
                Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                new SearchProperties(new SearchProperties.SavedSearches(20, Duration.ZERO)),
                entityManager);
        when(jdbcTemplate.queryForList(eq(SavedSearchService.TRY_SCAN_LOCK), eq(Boolean.class), anyString()))
                .thenReturn(List.of(Boolean.TRUE));
        givenTheLastScanWasAt(NOW);
        SavedSearch a = searchOf(USER, true);
        when(repository.findFirstAlertEnabledBatch(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));
        when(matcher.matches(any(SearchCriteria.class), eq(LISTING), eq(PROVIDER))).thenReturn(false);

        unclocked.processListingActivated(LISTING, PROVIDER);

        verify(repository).findFirstAlertEnabledBatch(any(Pageable.class));
    }
}
