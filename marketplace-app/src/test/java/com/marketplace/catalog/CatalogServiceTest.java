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
@ContextConfiguration(classes = { CatalogService.class })
class CatalogServiceTest {

    @Autowired
    private CatalogService catalogService;

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
}
