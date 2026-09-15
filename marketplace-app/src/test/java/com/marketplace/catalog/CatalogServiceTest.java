package com.marketplace.catalog;

import com.marketplace.shared.api.ProviderListingSummary;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { CatalogService.class, CatalogServiceTest.TestBeans.class })
class CatalogServiceTest {

    @Autowired
    private CatalogService catalogService;

    /**
     * L33: the service's new constructor dependencies — the real system
     * clock (the tests that need boundary control inject fixed values
     * through the service arguments, not the clock) and the lifecycle
     * policy (90-day window, 1-day cooldown — the house default shape).
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestBeans {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }

        @org.springframework.context.annotation.Bean
        CatalogProperties catalogProperties() {
            return new CatalogProperties(new CatalogProperties.Expiry(90, 1));
        }
    }

    @MockitoBean
    private ProviderListingRepository listingRepository;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ProviderNameResolver providerNameResolver;

    @MockitoBean
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private ProviderLookupPort providerLookupPort;

    // L38: the completeness computation's two cross-module ports
    // (realestate implements PropertyDetailsPort, media implements
    // MediaLookupPort at runtime — the slice mocks the contracts).
    @MockitoBean
    private com.marketplace.shared.api.PropertyDetailsPort propertyDetailsPort;

    @MockitoBean
    private com.marketplace.shared.api.MediaLookupPort mediaLookupPort;

    private ProviderListing listing(ListingStatus status) {
        return Instancio.of(ProviderListing.class)
                .set(field(ProviderListing::getStatus), status)
                .create();
    }

    @Test
    void findAllSummaries_returnsAllStatusesForAdmin() {
        ProviderListing draft = listing(ListingStatus.DRAFT);
        ProviderListing active = listing(ListingStatus.ACTIVE);
        ProviderListing archived = listing(ListingStatus.ARCHIVED);
        when(listingRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(draft, active, archived)));

        var summaries = catalogService.findAllSummaries(PageRequest.of(0, 20));

        assertThat(summaries).hasSize(3);
        assertThat(summaries.map(ProviderListingSummary::status))
                .containsExactly("DRAFT", "ACTIVE", "ARCHIVED");
    }

    /**
     * L36 (realestate systems plan §5): the shared-api port form of the
     * public provider-listings read — the same ACTIVE-only repository
     * contract as the REST surface, mapped to the summaries the provider
     * module's public page composes. CodeRabbit round-1 adoption: the
     * effective pageable is deterministic (unsorted defaults to id ASC —
     * the L32 total-order rule; offset pagination never duplicates or
     * omits rows across pages).
     */
    @Test
    void listActiveByProvider_queriesActiveOnlyMapsSummariesAndSortsDeterministically() {
        UUID providerUserId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        ProviderListing active = listing(ListingStatus.ACTIVE);
        var captured = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(listingRepository.findByProviderIdAndStatus(eq(providerUserId), eq(ListingStatus.ACTIVE), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(active),
                        inv.getArgument(2, Pageable.class), 1));

        var page = catalogService.listActiveByProvider(providerUserId, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement()
                .satisfies(summary -> assertThat(summary.id()).isEqualTo(active.getId()));
        // The effective sort the repository saw: unsorted request -> id ASC
        // (the deterministic total order), same page number/size.
        org.mockito.Mockito.verify(listingRepository)
                .findByProviderIdAndStatus(eq(providerUserId), eq(ListingStatus.ACTIVE), captured.capture());
        var effective = captured.getValue();
        assertThat(effective.getPageNumber()).isZero();
        assertThat(effective.getPageSize()).isEqualTo(20);
        assertThat(effective.getSort().isSorted()).isTrue();
        // Sort.getOrderFor returns the (nullable) Order directly — a total
        // id ASC order must be present.
        org.assertj.core.api.Assertions.assertThat(effective.getSort().getOrderFor("id"))
                .isNotNull()
                .extracting(org.springframework.data.domain.Sort.Order::getDirection)
                .isEqualTo(org.springframework.data.domain.Sort.Direction.ASC);
    }

    @Test
    void getActiveById_whenActive_returnsView() {
        ProviderListing active = listing(ListingStatus.ACTIVE);
        when(listingRepository.findById(active.getId())).thenReturn(Optional.of(active));

        ProviderListingView result = catalogService.getActiveById(active.getId());

        assertThat(result.id()).isEqualTo(active.getId());
        assertThat(result.status()).isEqualTo(ListingStatus.ACTIVE.name());
    }

    @Test
    void getActiveById_whenDraft_throwsNotFound() {
        ProviderListing draft = listing(ListingStatus.DRAFT);
        when(listingRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> catalogService.getActiveById(draft.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getActiveById_whenUnknownId_throwsNotFound() {
        when(listingRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalogService.getActiveById(java.util.UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- L27: the window-restricted search surface ---------------------------

    private static final java.util.Set<java.util.UUID> PROVIDER_IDS =
            java.util.Set.of(java.util.UUID.randomUUID(), java.util.UUID.randomUUID());

    @Test
    void searchByCriteriaRestricted_mapsPricesAndDelegatesWithTheWhitelist() {
        when(listingRepository.searchByCriteriaRestricted(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(listing(ListingStatus.ACTIVE))));

        var criteria = new com.marketplace.shared.api.SearchCriteria(
                null, null, java.math.BigDecimal.valueOf(10), java.math.BigDecimal.valueOf(20));

        var result = catalogService.searchByCriteriaRestricted(criteria, PROVIDER_IDS, PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        // BigDecimal 10 -> 1000 cents: the same movePointRight(2) mapping as
        // the unrestricted path rides the restricted query. guests rides
        // through as null (criterion-less).
        verify(listingRepository).searchByCriteriaRestricted(
                eq(null), eq(1000L), eq(2000L), eq(null), eq(PROVIDER_IDS), eq(PageRequest.of(0, 10)));
    }

    @Test
    void searchByCriteriaRestricted_guestsRideThroughToTheQuery() {
        when(listingRepository.searchByCriteriaRestricted(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        // A windowless guests-only criterion (I6).
        var criteria = new com.marketplace.shared.api.SearchCriteria(
                null, null, null, null, null, null, 4);

        catalogService.searchByCriteriaRestricted(criteria, PROVIDER_IDS, PageRequest.of(0, 10));

        verify(listingRepository).searchByCriteriaRestricted(
                eq(null), eq(null), eq(null), eq(4), eq(PROVIDER_IDS), eq(PageRequest.of(0, 10)));
    }

    @Test
    void searchFullTextRestricted_keepsTheTrigramFallbackOnAnEmptyPage() {
        // Zero TOTAL matches — the pg_trgm fallback runs, exactly like the
        // unrestricted searchFullText.
        when(listingRepository.searchFullTextRestricted(anyString(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(listingRepository.searchSimilarRestricted(anyString(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(listing(ListingStatus.ACTIVE))));

        var result = catalogService.searchFullTextRestricted("gardn", PROVIDER_IDS, PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        verify(listingRepository).searchFullTextRestricted(eq("gardn"), eq(PROVIDER_IDS), eq(PageRequest.of(0, 10)));
        verify(listingRepository).searchSimilarRestricted(eq("gardn"), eq(PROVIDER_IDS), eq(PageRequest.of(0, 10)));
    }

    @Test
    void searchFullTextRestricted_outOfRangePageOverRealMatches_staysEmpty_noFallback() {
        // Matches exist (total 1) but the requested page is past them: the
        // content is legitimately empty — the fallback must NOT replace it
        // with a different result set (PR #256 full-review round:
        // isEmpty() is true while totalElements > 0).
        when(listingRepository.searchFullTextRestricted(anyString(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 10), 1));

        var result = catalogService.searchFullTextRestricted("garden", PROVIDER_IDS, PageRequest.of(5, 10));

        assertThat(result).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(listingRepository, never()).searchSimilarRestricted(anyString(), any(), any());
    }

    @Test
    void searchFullTextRestricted_noFallbackWhenFtsMatches() {
        when(listingRepository.searchFullTextRestricted(anyString(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(listing(ListingStatus.ACTIVE))));

        var result = catalogService.searchFullTextRestricted("garden", PROVIDER_IDS, PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        verify(listingRepository, never()).searchSimilarRestricted(anyString(), any(), any());
    }

    // ---- I6: the guest-capacity write path ------------------------------------

    @Test
    void create_withCapacity_savesAndTheViewCarriesIt() {
        stubVerifiedProvider();
        when(listingRepository.save(any(ProviderListing.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = catalogService.create(java.util.UUID.randomUUID(), "Loft", "desc",
                "stay", 10_000L, null, 4);

        assertThat(view.maxGuests()).isEqualTo(4);
        verify(listingRepository).save(org.mockito.ArgumentMatchers.argThat(
                l -> l != null && java.util.Objects.equals(l.getMaxGuests(), 4)));
    }

    @Test
    void create_withoutCapacity_leavesItUndeclared() {
        stubVerifiedProvider();
        when(listingRepository.save(any(ProviderListing.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = catalogService.create(java.util.UUID.randomUUID(), "Loft", "desc",
                "stay", 10_000L, null, null);

        assertThat(view.maxGuests()).isNull();
    }

    @Test
    void create_withNonPositiveCapacity_failsAtTheEntityFloor() {
        // The write-side gate layer 2: the entity factory floor rejects a
        // non-positive capacity even if a caller bypassed Bean Validation
        // (layer 3 is the V44 CHECK constraint).
        stubVerifiedProvider();

        assertThatThrownBy(() -> catalogService.create(java.util.UUID.randomUUID(), "Loft", "desc",
                "stay", 10_000L, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max guests must be positive");
        verify(listingRepository, never()).save(any());
    }

    @Test
    void update_withOmittedCapacity_keepsTheStoredOne() {
        // The currency contract verbatim: an update that omits maxGuests
        // does not reset the stored capacity.
        ProviderListing stored = listing(ListingStatus.ACTIVE);
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        stubOwnership(stored);

        Integer before = stored.getMaxGuests(); // captured BEFORE (no tautology)
        catalogService.update(stored.getId(), "Loft 2", "desc", "stay", 12_000L, null, null, null);

        // omitted (null) — the entity keeps whatever it had (Instancio's
        // random value survives; nothing was overwritten with null).
        assertThat(stored.getMaxGuests()).isEqualTo(before);
    }

    @Test
    void update_withExplicitCapacity_redeclaresIt() {
        ProviderListing stored = listing(ListingStatus.ACTIVE);
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        stubOwnership(stored);

        catalogService.update(stored.getId(), "Loft 2", "desc", "stay", 12_000L, null, 6, null);

        assertThat(stored.getMaxGuests()).isEqualTo(6);
    }

    @Test
    void update_withNonPositiveCapacity_failsAtTheEntityFloor() {
        ProviderListing stored = listing(ListingStatus.ACTIVE);
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        stubOwnership(stored);

        Integer before = stored.getMaxGuests(); // captured BEFORE (no tautology)
        assertThatThrownBy(() -> catalogService.update(stored.getId(), "Loft 2", "desc", "stay",
                12_000L, null, -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max guests must be positive");
        assertThat(stored.getMaxGuests()).isEqualTo(before);
    }

    private void stubVerifiedProvider() {
        var summary = new com.marketplace.shared.api.ProviderSummary(
                java.util.UUID.randomUUID(), "provider", "VERIFIED", null);
        when(providerLookupPort.findByUserId(any())).thenReturn(Optional.of(summary));
    }

    private void stubOwnership(ProviderListing listing) {
        var owner = new com.marketplace.shared.api.ProviderSummary(
                java.util.UUID.randomUUID(), "provider", "VERIFIED", listing.getProviderId());
        when(currentUserProvider.isAdmin(any())).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(listing.getProviderId());
        when(providerLookupPort.findByUserId(listing.getProviderId())).thenReturn(Optional.of(owner));
    }

    // ---- L38: the completeness read -------------------------------------------

    /**
     * The composed read: the stored listing + the UPLOADED photo count +
     * the optional property block ride the documented equation — here the
     * full fixture (description present, one photo, block with location)
     * answers the full score.
     */
    @Test
    void getCompleteness_composesTheThreeReadsIntoTheScore() {
        ProviderListing stored = ProviderListing.create(
                java.util.UUID.randomUUID(), "Villa", "sea view", "APARTMENT", 1000L);
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        stubOwnership(stored);
        when(mediaLookupPort.countUploadedByListing(stored.getId())).thenReturn(1L);
        when(propertyDetailsPort.findByListingId(stored.getId()))
                .thenReturn(Optional.of(propertyWithLocation(stored.getId())));
        var score = catalogService.getCompleteness(
                stored.getId(), org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class));

        assertThat(score.percent()).isEqualTo(100);
        assertThat(score.coreFieldsPresent()).isTrue();
        assertThat(score.photosPresent()).isTrue();
        assertThat(score.propertyDetailsPresent()).isTrue();
        assertThat(score.locationPresent()).isTrue();
        verify(mediaLookupPort).countUploadedByListing(stored.getId());
        verify(propertyDetailsPort).findByListingId(stored.getId());
    }

    /**
     * The partial fixture: description present, but no photos and no
     * property block — the plan's criterion-2 boundary (25, the low
     * quarter score with three of the four groups missing).
     */
    @Test
    void getCompleteness_partialListing_answersTheBoundaryScore() {
        ProviderListing stored = ProviderListing.create(
                java.util.UUID.randomUUID(), "Villa", "sea view", "APARTMENT", 1000L);
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        stubOwnership(stored);
        when(mediaLookupPort.countUploadedByListing(stored.getId())).thenReturn(0L);
        when(propertyDetailsPort.findByListingId(stored.getId())).thenReturn(Optional.empty());

        var score = catalogService.getCompleteness(
                stored.getId(), org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class));

        assertThat(score.percent()).isEqualTo(25);
        assertThat(score.photosPresent()).isFalse();
        assertThat(score.propertyDetailsPresent()).isFalse();
    }

    /** Unknown id: the ownership read's own 404 (before any port call). */
    @Test
    void getCompleteness_unknownId_throwsNotFound() {
        when(listingRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalogService.getCompleteness(
                java.util.UUID.randomUUID(),
                org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(mediaLookupPort, never()).countUploadedByListing(any());
    }

    /**
     * Foreign provider: the update/pause/renew ownership contract — the
     * current user is NOT the listing's owner, so 403 before any scoring.
     */
    @Test
    void getCompleteness_foreignProvider_throwsAccessDenied() {
        ProviderListing stored = listing(ListingStatus.ACTIVE);
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        // The foreign caller: a different user id than the owner's.
        when(currentUserProvider.isAdmin(any())).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(java.util.UUID.randomUUID());
        when(providerLookupPort.findByUserId(stored.getProviderId())).thenReturn(Optional.of(
                new com.marketplace.shared.api.ProviderSummary(
                        java.util.UUID.randomUUID(), "provider", "VERIFIED", stored.getProviderId())));

        assertThatThrownBy(() -> catalogService.getCompleteness(
                stored.getId(), org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(mediaLookupPort, never()).countUploadedByListing(any());
    }

    private static com.marketplace.shared.api.PropertyDetailsPort.PropertyView propertyWithLocation(
            UUID listingId) {
        return new com.marketplace.shared.api.PropertyDetailsPort.PropertyView(
                listingId,
                com.marketplace.shared.api.PropertyPurpose.RENT,
                com.marketplace.shared.api.PropertyType.APARTMENT,
                120, 3, 2, 1, 4, 2015, true,
                java.util.List.of("elevator"), null,
                java.util.UUID.randomUUID(), null, null);
    }

}
