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
            return new CatalogProperties(new CatalogProperties.Expiry(90, 1),
                    new CatalogProperties.Seo("", "/listings/{id}", java.util.List.of()),
                    new CatalogProperties.Views("test-key", java.time.Duration.ofDays(1)));
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
        // S7: the money shape is complete — every summary carries the
        // listing's ISO 4217 currency next to its price.
        assertThat(summaries.map(ProviderListingSummary::currency))
                .containsExactly(draft.getCurrency(), active.getCurrency(), archived.getCurrency());
    }

    /**
     * L36 (realestate systems plan §5): the shared-api port form of the
     * public provider-listings read — the same ACTIVE-only predicate
     * contract as the REST surface, mapped to the summaries the provider
     * module's public page composes.
     *
     * <p>L37: the surface rides the two-specification boost-first read
     * now — the ordering (boost flag + requested sort + the id ASC
     * tiebreak, unsorted defaulting to id ASC) lives INSIDE the content
     * specification, so the pageable the repository sees is UNSORTED
     * (page/size only: a sorted Pageable would REPLACE the
     * specification's order — the measured SimpleJpaRepository chain,
     * documented on findBoostFirst). The ordering itself is proven on
     * the real database by BoostOrderingIntegrationTest.
     */
    @Test
    void listActiveByProvider_queriesActiveOnlyMapsSummariesThroughTheBoostFirstRead() {
        UUID providerUserId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        ProviderListing active = listing(ListingStatus.ACTIVE);
        when(listingRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(active),
                        inv.getArgument(2, Pageable.class), 1));

        var page = catalogService.listActiveByProvider(providerUserId, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement()
                .satisfies(summary -> assertThat(summary.id()).isEqualTo(active.getId()));
        // The pageable the repository saw: same page/size, UNSORTED — the
        // total order is the boost specification's own contract now.
        var captured = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(listingRepository).findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.jpa.domain.Specification.class),
                captured.capture());
        var effective = captured.getValue();
        assertThat(effective.getPageNumber()).isZero();
        assertThat(effective.getPageSize()).isEqualTo(20);
        assertThat(effective.getSort().isSorted()).isFalse();
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
        when(listingRepository.searchByCriteriaRestricted(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(listing(ListingStatus.ACTIVE))));

        var criteria = new com.marketplace.shared.api.SearchCriteria(
                null, null, java.math.BigDecimal.valueOf(10), java.math.BigDecimal.valueOf(20));

        var result = catalogService.searchByCriteriaRestricted(criteria, PROVIDER_IDS, PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        // BigDecimal 10 -> 1000 cents: the same movePointRight(2) mapping as
        // the unrestricted path rides the restricted query. guests rides
        // through as null (criterion-less). The :now instant (L37) is the
        // service clock's own reading — asserted only as "present" here;
        // the boost semantics it feeds are integration-proven.
        verify(listingRepository).searchByCriteriaRestricted(
                eq(null), eq(1000L), eq(2000L), eq(null), eq(PROVIDER_IDS), any(java.time.Instant.class),
                eq(PageRequest.of(0, 10)));
    }

    @Test
    void searchByCriteriaRestricted_guestsRideThroughToTheQuery() {
        when(listingRepository.searchByCriteriaRestricted(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        // A windowless guests-only criterion (I6).
        var criteria = new com.marketplace.shared.api.SearchCriteria(
                null, null, null, null, null, null, 4);

        catalogService.searchByCriteriaRestricted(criteria, PROVIDER_IDS, PageRequest.of(0, 10));

        verify(listingRepository).searchByCriteriaRestricted(
                eq(null), eq(null), eq(null), eq(4), eq(PROVIDER_IDS), any(java.time.Instant.class),
                eq(PageRequest.of(0, 10)));
    }

    @Test
    void searchFullTextRestricted_keepsTheTrigramFallbackOnAnEmptyPage() {
        // Zero TOTAL matches — the pg_trgm fallback runs, exactly like the
        // unrestricted searchFullText.
        when(listingRepository.searchFullTextRestricted(anyString(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(listingRepository.searchSimilarRestricted(anyString(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(listing(ListingStatus.ACTIVE))));

        var result = catalogService.searchFullTextRestricted("gardn", PROVIDER_IDS, PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        verify(listingRepository).searchFullTextRestricted(eq("gardn"), eq(PROVIDER_IDS),
                any(java.time.Instant.class), eq(PageRequest.of(0, 10)));
        verify(listingRepository).searchSimilarRestricted(eq("gardn"), eq(PROVIDER_IDS),
                any(java.time.Instant.class), eq(PageRequest.of(0, 10)));
    }

    @Test
    void searchFullTextRestricted_outOfRangePageOverRealMatches_staysEmpty_noFallback() {
        // Matches exist (total 1) but the requested page is past them: the
        // content is legitimately empty — the fallback must NOT replace it
        // with a different result set (PR #256 full-review round:
        // isEmpty() is true while totalElements > 0).
        when(listingRepository.searchFullTextRestricted(anyString(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(5, 10), 1));

        var result = catalogService.searchFullTextRestricted("garden", PROVIDER_IDS, PageRequest.of(5, 10));

        assertThat(result).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(listingRepository, never()).searchSimilarRestricted(anyString(), any(), any(), any());
    }

    @Test
    void searchFullTextRestricted_noFallbackWhenFtsMatches() {
        when(listingRepository.searchFullTextRestricted(anyString(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(listing(ListingStatus.ACTIVE))));

        var result = catalogService.searchFullTextRestricted("garden", PROVIDER_IDS, PageRequest.of(0, 10));

        assertThat(result).hasSize(1);
        verify(listingRepository, never()).searchSimilarRestricted(anyString(), any(), any(), any());
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

    // ---- L38: the owned-listing read (the completeness surface's gate) --------

    /**
     * The ownership read returns the listing itself in ANY status — the
     * score guides completion before activation, so the public ACTIVE-only
     * gate does not apply (a DRAFT is readable by its owner).
     */
    @Test
    void getOwnedListing_returnsTheListingToItsOwner() {
        ProviderListing stored = ProviderListing.create(
                java.util.UUID.randomUUID(), "Villa", "sea view", "APARTMENT", 1000L);
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        stubOwnership(stored);

        ProviderListing result = catalogService.getOwnedListing(
                stored.getId(), org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class));

        assertThat(result).isSameAs(stored);
    }

    /** Unknown id: the ownership read's own 404. */
    @Test
    void getOwnedListing_unknownId_throwsNotFound() {
        when(listingRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalogService.getOwnedListing(
                java.util.UUID.randomUUID(),
                org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Foreign provider: the update/pause/renew ownership contract — the
     * current user is NOT the listing's owner, so 403 before any scoring.
     */
    @Test
    void getOwnedListing_foreignProvider_throwsAccessDenied() {
        ProviderListing stored = listing(ListingStatus.ACTIVE);
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        // The foreign caller: a different user id than the owner's.
        when(currentUserProvider.isAdmin(any())).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(java.util.UUID.randomUUID());
        when(providerLookupPort.findByUserId(stored.getProviderId())).thenReturn(Optional.of(
                new com.marketplace.shared.api.ProviderSummary(
                        java.util.UUID.randomUUID(), "provider", "VERIFIED", stored.getProviderId())));

        assertThatThrownBy(() -> catalogService.getOwnedListing(
                stored.getId(), org.mockito.Mockito.mock(org.springframework.security.core.Authentication.class)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    // ---- L37: the administrative boost shading point -------------------------

    /**
     * The happy shading: a future window at the injected clock lands on the
     * entity and answers the resulting state. The cache eviction rides the
     * standard relay — proven END-TO-END by BoostOrderingIntegrationTest
     * (the cached surface's ordering flips after the shading, which IS the
     * AFTER_COMMIT relay working); this context's @MockitoBean
     * ApplicationEventPublisher does not intercept the context's own
     * publisher (the resolvable-dependency seam — the house verifies
     * events on manually-constructed services, e.g.
     * ListingExpiryPolicyTest, or end-to-end).
     */
    @Test
    void setListingPromotion_futureWindow_setsAndReturnsState() {
        ProviderListing stored = listing(ListingStatus.ACTIVE);
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        java.time.Instant until = java.time.Instant.now()
                .plus(java.time.Duration.ofDays(7));

        var result = catalogService.setListingPromotion(stored.getId(), until);

        assertThat(result.id()).isEqualTo(stored.getId());
        assertThat(result.promotedUntil()).isEqualTo(until);
        assertThat(stored.getPromotedUntil()).isEqualTo(until);
    }

    /**
     * The boundary rule (the activate/renew contract verbatim): a window
     * that is not strictly future at the injected clock is a 400 — a past
     * window would be a silent no-op ordering.
     */
    @Test
    void setListingPromotion_pastWindow_throwsBadRequest() {
        ProviderListing stored = listing(ListingStatus.ACTIVE);
        // Instancio seeds the new nullable field with a random Instant —
        // the 400 contract is "unchanged", not "null" (the rejection must
        // never mutate the entity).
        java.time.Instant before = stored.getPromotedUntil();
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> catalogService.setListingPromotion(
                stored.getId(), java.time.Instant.now().minusSeconds(60)))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("strictly in the future");
        assertThat(stored.getPromotedUntil()).isEqualTo(before);
    }

    /**
     * The clear path: a null until removes the boost (the admin's
     * correction exit — the L36 optional-field PUT contract).
     */
    @Test
    void setListingPromotion_nullWindow_clears() {
        ProviderListing stored = listing(ListingStatus.ACTIVE);
        stored.promoteUntil(java.time.Instant.now().plusSeconds(3600));
        when(listingRepository.findById(stored.getId())).thenReturn(Optional.of(stored));

        var result = catalogService.setListingPromotion(stored.getId(), null);

        assertThat(result.promotedUntil()).isNull();
        assertThat(stored.getPromotedUntil()).isNull();
    }

    /** Unknown listing: the archive family's 404 contract. */
    @Test
    void setListingPromotion_unknownListing_throwsNotFound() {
        when(listingRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalogService.setListingPromotion(
                java.util.UUID.randomUUID(), java.time.Instant.now().plusSeconds(3600)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

}
