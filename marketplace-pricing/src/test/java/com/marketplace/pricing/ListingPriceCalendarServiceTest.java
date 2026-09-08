package com.marketplace.pricing;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L26 (feature-expansion roadmap §5): unit coverage of the price-calendar
 * service — the module's own jacoco bundle (the deep loop is covered by the
 * app-level integration test on real V41; this pins the same policy on the
 * service seams: the ownership triple (404 unknown listing via the catalog
 * port, 403 foreign listing via the MediaService convention, admin pass),
 * the overlap policy (409 on a real overlap, adjacency legal, self-excluded
 * update), the upsert lifecycle, and the cache eviction event on EVERY
 * write (roadmap criterion 4).
 */
class ListingPriceCalendarServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final UUID LISTING = UUID.randomUUID();

    private final ListingWeekendRuleRepository weekendRuleRepository = mock(ListingWeekendRuleRepository.class);
    private final SeasonalRateRepository seasonalRateRepository = mock(SeasonalRateRepository.class);
    private final ListingPriceProvider listingPriceProvider = mock(ListingPriceProvider.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ProviderLookupPort providerLookupPort = mock(ProviderLookupPort.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final ListingPriceCalendarService service = new ListingPriceCalendarService(
            weekendRuleRepository, seasonalRateRepository, listingPriceProvider,
            currentUserProvider, providerLookupPort, eventPublisher);

    private final Authentication authentication = mock(Authentication.class);

    @BeforeEach
    void actingAsOwner() {
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(OWNER);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
        when(listingPriceProvider.getListingInfo(LISTING))
                .thenReturn(new ListingPriceProvider.ListingInfo(OWNER, 10_000L));
        when(providerLookupPort.findByUserId(OWNER)).thenReturn(Optional.of(
                new ProviderSummary(UUID.randomUUID(), "L26 Host", "VERIFIED", OWNER)));
    }

    private void listingOwnedByForeignUser() {
        when(listingPriceProvider.getListingInfo(LISTING))
                .thenReturn(new ListingPriceProvider.ListingInfo(STRANGER, 10_000L));
        when(providerLookupPort.findByUserId(STRANGER)).thenReturn(Optional.of(
                new ProviderSummary(UUID.randomUUID(), "Foreign", "VERIFIED", STRANGER)));
    }

    private void assertEvicted() {
        ArgumentCaptor<CacheInvalidationRequested> captor =
                ArgumentCaptor.forClass(CacheInvalidationRequested.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertTrue(captor.getValue().cacheNames().contains("pricing-calculations"));
    }

    @Test
    void getCalendar_noRules_isTheFlatModel() {
        when(weekendRuleRepository.findByListingId(LISTING)).thenReturn(Optional.empty());
        when(seasonalRateRepository.findByListingIdOrderByFromDateAsc(LISTING)).thenReturn(List.of());

        ListingCalendarResponse calendar = service.getCalendar(LISTING, authentication);

        assertEquals(LISTING, calendar.listingId());
        assertNull(calendar.weekendRule(), "no weekend rule — the flat model");
        assertTrue(calendar.seasonalRates().isEmpty());
    }

    @Test
    void getCalendar_returnsBothHalves() {
        ListingWeekendRule rule = ListingWeekendRule.create(LISTING, new BigDecimal("1.2"));
        SeasonalRate rate = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L);
        when(weekendRuleRepository.findByListingId(LISTING)).thenReturn(Optional.of(rule));
        when(seasonalRateRepository.findByListingIdOrderByFromDateAsc(LISTING)).thenReturn(List.of(rate));

        ListingCalendarResponse calendar = service.getCalendar(LISTING, authentication);

        assertEquals(new BigDecimal("1.2"), calendar.weekendRule().multiplier());
        assertEquals(20_000L, calendar.seasonalRates().getFirst().priceCents());
    }

    @Test
    void upsertWeekendRule_createsWhenAbsent_andEvicts() {
        when(weekendRuleRepository.findByListingId(LISTING)).thenReturn(Optional.empty());
        when(weekendRuleRepository.save(any(ListingWeekendRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WeekendRuleResponse response = service.upsertWeekendRule(LISTING, new BigDecimal("1.2"), authentication);

        assertEquals(new BigDecimal("1.2"), response.multiplier());
        assertEquals(LISTING, response.listingId());
        assertEvicted();
    }

    @Test
    void upsertWeekendRule_retunesTheExistingRow_andEvicts() {
        ListingWeekendRule existing = ListingWeekendRule.create(LISTING, new BigDecimal("1.2"));
        when(weekendRuleRepository.findByListingId(LISTING)).thenReturn(Optional.of(existing));
        when(weekendRuleRepository.save(any(ListingWeekendRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WeekendRuleResponse response = service.upsertWeekendRule(LISTING, new BigDecimal("1.5"), authentication);

        assertEquals(existing.getId(), response.id(), "upsert re-tunes the same row");
        assertEquals(new BigDecimal("1.5"), response.multiplier());
        assertEvicted();
    }

    @Test
    void deleteWeekendRule_softDeletes_andEvicts() {
        ListingWeekendRule existing = ListingWeekendRule.create(LISTING, new BigDecimal("1.2"));
        when(weekendRuleRepository.findByListingId(LISTING)).thenReturn(Optional.of(existing));

        service.deleteWeekendRule(LISTING, authentication);

        verify(weekendRuleRepository).deleteById(existing.getId());
        assertEvicted();
    }

    @Test
    void deleteWeekendRule_noRule_is404() {
        when(weekendRuleRepository.findByListingId(LISTING)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.deleteWeekendRule(LISTING, authentication));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void addSeasonalRate_saves_andEvicts() {
        when(seasonalRateRepository.findByListingIdOrderByFromDateAsc(LISTING)).thenReturn(List.of());
        when(seasonalRateRepository.save(any(SeasonalRate.class))).thenAnswer(inv -> inv.getArgument(0));

        SeasonalRateResponse response = service.addSeasonalRate(LISTING,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L, authentication);

        assertEquals(20_000L, response.priceCents());
        assertEvicted();
    }

    @Test
    void addSeasonalRate_realOverlap_is409_andNothingSaved() {
        SeasonalRate sibling = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 25_000L);
        when(seasonalRateRepository.findByListingIdOrderByFromDateAsc(LISTING)).thenReturn(List.of(sibling));

        // Shares Jan 14 with the sibling — one day is enough.
        assertThrows(ConflictException.class, () -> service.addSeasonalRate(LISTING,
                LocalDate.parse("2026-01-14"), LocalDate.parse("2026-01-20"), 18_000L, authentication));
        verify(seasonalRateRepository, never()).save(any(SeasonalRate.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void addSeasonalRate_adjacentOnSharedBoundary_isLegal() {
        SeasonalRate sibling = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 25_000L);
        when(seasonalRateRepository.findByListingIdOrderByFromDateAsc(LISTING)).thenReturn(List.of(sibling));
        when(seasonalRateRepository.save(any(SeasonalRate.class))).thenAnswer(inv -> inv.getArgument(0));

        // Starts exactly where the sibling ends — open intervals.
        assertDoesNotThrow(() -> service.addSeasonalRate(LISTING,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-20"), 20_000L, authentication));
    }

    @Test
    void addSeasonalRate_malformedRange_is400_not409() {
        assertThrows(IllegalArgumentException.class, () -> service.addSeasonalRate(LISTING,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-15"), 20_000L, authentication));
        verify(seasonalRateRepository, never()).save(any(SeasonalRate.class));
    }

    @Test
    void updateSeasonalRate_excludesItselfFromTheOverlapWalk() {
        SeasonalRate own = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 25_000L);
        SeasonalRate sibling = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-20"), 22_000L);
        when(seasonalRateRepository.findById(own.getId())).thenReturn(Optional.of(own));
        when(seasonalRateRepository.findByListingIdOrderByFromDateAsc(LISTING))
                .thenReturn(List.of(own, sibling));
        when(seasonalRateRepository.save(any(SeasonalRate.class))).thenAnswer(inv -> inv.getArgument(0));

        // Extending `own` by one day (to Jan 11→15) — its own span never counts as
        // its own overlap; the sibling starts where it ends — adjacency legal.
        SeasonalRateResponse response = service.updateSeasonalRate(LISTING, own.getId(),
                LocalDate.parse("2026-01-11"), LocalDate.parse("2026-01-15"), 26_000L, authentication);

        assertEquals(26_000L, response.priceCents());
        assertEvicted();
    }

    @Test
    void updateSeasonalRate_belongsToAnotherListing_is404() {
        SeasonalRate foreign = SeasonalRate.create(UUID.randomUUID(),
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 25_000L);
        when(seasonalRateRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThrows(ResourceNotFoundException.class, () -> service.updateSeasonalRate(
                LISTING, foreign.getId(),
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 25_000L, authentication));
    }

    @Test
    void updateSeasonalRate_realOverlap_is409() {
        SeasonalRate own = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-12"), 25_000L);
        SeasonalRate sibling = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-12"), LocalDate.parse("2026-01-20"), 22_000L);
        when(seasonalRateRepository.findById(own.getId())).thenReturn(Optional.of(own));
        when(seasonalRateRepository.findByListingIdOrderByFromDateAsc(LISTING)).thenReturn(List.of(sibling));

        // Moving `own` to overlap the sibling by one day (Jan 13..14) — 409.
        assertThrows(ConflictException.class, () -> service.updateSeasonalRate(LISTING, own.getId(),
                LocalDate.parse("2026-01-13"), LocalDate.parse("2026-01-14"), 25_000L, authentication));
        verify(seasonalRateRepository, never()).save(any(SeasonalRate.class));
    }

    @Test
    void deleteSeasonalRate_softDeletes_andEvicts() {
        SeasonalRate own = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 25_000L);
        when(seasonalRateRepository.findById(own.getId())).thenReturn(Optional.of(own));

        service.deleteSeasonalRate(LISTING, own.getId(), authentication);

        verify(seasonalRateRepository).deleteById(own.getId());
        assertEvicted();
    }

    @Test
    void foreignListing_is403_andNothingTouched() {
        listingOwnedByForeignUser();

        assertAll(
                () -> assertThrows(AccessDeniedException.class,
                        () -> service.getCalendar(LISTING, authentication)),
                () -> assertThrows(AccessDeniedException.class,
                        () -> service.upsertWeekendRule(LISTING, new BigDecimal("1.2"), authentication)),
                () -> assertThrows(AccessDeniedException.class,
                        () -> service.addSeasonalRate(LISTING,
                                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 25_000L, authentication)));
        verify(weekendRuleRepository, never()).save(any(ListingWeekendRule.class));
        verify(seasonalRateRepository, never()).save(any(SeasonalRate.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void adminBypassesOwnership() {
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(true);
        when(weekendRuleRepository.findByListingId(LISTING)).thenReturn(Optional.empty());
        when(weekendRuleRepository.save(any(ListingWeekendRule.class))).thenAnswer(inv -> inv.getArgument(0));

        // The admin's own id resolves no profile — the bypass must not need one.
        assertDoesNotThrow(() -> service.upsertWeekendRule(LISTING, new BigDecimal("1.1"), authentication));
    }

    @Test
    void unknownListing_is404ThroughTheCatalogPort() {
        when(listingPriceProvider.getListingInfo(any(UUID.class)))
                .thenThrow(new ResourceNotFoundException("Listing", LISTING));

        assertThrows(ResourceNotFoundException.class,
                () -> service.getCalendar(LISTING, authentication));
    }

    /**
     * The race backstop's translation (CodeRabbit round 1): a violation of
     * the V41 live-range EXCLUDE constraint — the loser of two concurrent
     * overlapping writers — surfaces as the SAME 409 ConflictException the
     * sequential rejection answers with. Realistic chain: Hibernate does
     * not parse the constraint NAME for exclusion violations (null —
     * measured live), so the SQLState 23P01 + the constraint name in the
     * message carry the identity.
     */
    @Test
    void exclusionConstraintViolation_translatesToThe409Taxonomy() {
        when(seasonalRateRepository.findByListingIdOrderByFromDateAsc(LISTING)).thenReturn(List.of());
        String psqlMessage = "conflicting key value violates exclusion constraint \""
                + ListingPriceCalendarService.LIVE_RANGE_EXCLUSION_CONSTRAINT + "\"";
        // Plain SQLException carries the SQLState (the postgres driver is not
        // on this module's test classpath — the JDBC standard constructor
        // preserves it through Hibernate's wrapping, verified live).
        java.sql.SQLException psql = new java.sql.SQLException(psqlMessage, "23P01");
        when(seasonalRateRepository.save(any(SeasonalRate.class))).thenThrow(
                new DataIntegrityViolationException("could not execute statement [ERROR: " + psqlMessage + "]",
                        new org.hibernate.exception.ConstraintViolationException(
                                "could not execute statement", psql, null)));

        assertThrows(ConflictException.class, () -> service.addSeasonalRate(LISTING,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L, authentication));
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * The translation must NOT mask unrelated database errors — a different
     * constraint's violation (another SQLState / another name) surfaces
     * unchanged (CodeRabbit round 1: "while preserving other database
     * errors").
     */
    @Test
    void otherIntegrityViolations_surfaceUnchanged() {
        when(seasonalRateRepository.findByListingIdOrderByFromDateAsc(LISTING)).thenReturn(List.of());
        java.sql.SQLException psql = new java.sql.SQLException(
                "new row violates check constraint \"chk_seasonal_rates_range\"", "23514");
        when(seasonalRateRepository.save(any(SeasonalRate.class))).thenThrow(
                new DataIntegrityViolationException("could not execute statement [ERROR: check violation]",
                        new org.hibernate.exception.ConstraintViolationException(
                                "could not execute statement", psql, "chk_seasonal_rates_range")));

        assertThrows(DataIntegrityViolationException.class, () -> service.addSeasonalRate(LISTING,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L, authentication));
        verify(eventPublisher, never()).publishEvent(any());
    }
}
