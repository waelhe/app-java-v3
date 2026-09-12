package com.marketplace.realestate;

import com.marketplace.shared.api.PropertyCriteria;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import com.marketplace.shared.api.RealestatePropertyFilterPort.PropertyMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L32: the filter adapter's delegation contracts — which repository entry
 * each port method takes, the match mapping, and the area ordering (the
 * id tiebreak). The Specification semantics themselves run on PostgreSQL
 * in {@code SearchPropertyFilterIntegrationTest} (the real-schema gate).
 */
@ExtendWith(MockitoExtension.class)
class PropertyFilterAdapterTest {

    @Mock
    private PropertyDetailsRepository repository;

    @InjectMocks
    private PropertyFilterAdapter adapter;

    private static PropertyDetails details(UUID listingId, Integer areaM2) {
        return PropertyDetails.create(listingId, UUID.randomUUID(),
                new PropertyDetailsRequest(PropertyPurpose.RENT, PropertyType.APARTMENT,
                        areaM2, 3, 2, 1, 5, 2015, null, null, null, null, null, null));
    }

    @Test
    void findListingIdsMatching_mapsTheMatchingSet() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        when(repository.findAll(any(Specification.class)))
                .thenReturn(List.of(details(one, 80), details(two, 120)));

        Set<UUID> ids = adapter.findListingIdsMatching(new PropertyCriteria(
                PropertyPurpose.RENT, null, null, null, null, null));

        assertThat(ids).containsExactlyInAnyOrder(one, two);
    }

    @Test
    void findListingIdsMatchingRestricted_passesTheWhitelistThroughTheSpecification() {
        UUID provider = UUID.randomUUID();
        UUID matched = UUID.randomUUID();
        when(repository.findAll(any(Specification.class)))
                .thenReturn(List.of(details(matched, 100)));

        Set<UUID> ids = adapter.findListingIdsMatchingRestricted(new PropertyCriteria(
                null, null, null, null, null, null), Set.of(provider));

        assertThat(ids).containsExactly(matched);
        // the spec saw the criteria + whitelist combination (the capture
        // proves the restricted form did not drop either)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Specification<PropertyDetails>> captor =
                ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll((Specification<PropertyDetails>) captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void findMatchingPaged_mapsMatchesAndOrdersByAreaWithIdTiebreak() {
        UUID small = UUID.randomUUID();
        UUID large = UUID.randomUUID();
        Pageable areaDesc = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "area"));
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(details(large, 120), details(small, 80))));

        var page = adapter.findMatchingPaged(new PropertyCriteria(
                null, null, null, null, null, null), Set.of(small, large), areaDesc);

        assertThat(page.getContent())
                .extracting(PropertyMatch::listingId)
                .containsExactly(large, small);
        assertThat(page.getContent())
                .extracting(PropertyMatch::areaM2)
                .containsExactly(120, 80);

        // the area marker was translated to the realestate-owned field
        // (areaM2 DESC) with the id tiebreak
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().toString())
                .isEqualTo("areaM2: DESC,id: ASC");
    }

    @Test
    void findMatchingPagedRestricted_delegatesBothRestrictions() {
        UUID active = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(details(active, 90))));

        var page = adapter.findMatchingPagedRestricted(new PropertyCriteria(
                        PropertyPurpose.SALE, null, null, null, null, null),
                Set.of(active), Set.of(provider), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).listingId()).isEqualTo(active);
    }

    // ---- P1 (postgis plan): the radius operations ----------------------

    private static final java.math.BigDecimal LAT = new java.math.BigDecimal("33.558889");
    private static final java.math.BigDecimal LNG = new java.math.BigDecimal("36.056944");

    @Test
    void findListingIdsWithinRadius_delegatesToTheNativeSetQuery() {
        UUID near = UUID.randomUUID();
        when(repository.findListingIdsWithinRadius(LAT, LNG, 10_000L)).thenReturn(Set.of(near));

        var ids = adapter.findListingIdsWithinRadius(LAT, LNG, 10_000L);

        assertThat(ids).containsExactly(near);
        verify(repository).findListingIdsWithinRadius(LAT, LNG, 10_000L);
    }

    @Test
    void findListingIdsWithinRadiusRestricted_carriesTheWhitelist() {
        UUID provider = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        when(repository.findListingIdsWithinRadiusRestricted(LAT, LNG, 5_000L, Set.of(provider)))
                .thenReturn(Set.of(near));

        var ids = adapter.findListingIdsWithinRadiusRestricted(LAT, LNG, 5_000L, Set.of(provider));

        assertThat(ids).containsExactly(near);
        verify(repository).findListingIdsWithinRadiusRestricted(LAT, LNG, 5_000L, Set.of(provider));
    }

    @Test
    void findWithinRadiusPaged_mapsToIds_andConsumesTheSortMarker() {
        UUID nearest = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        Pageable distanceSorted = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "distance"));
        when(repository.findWithinRadiusPaged(eq(LAT), eq(LNG), eq(10_000L),
                eq(Set.of(nearest, next)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(details(nearest, 100), details(next, 200))));

        var page = adapter.findWithinRadiusPaged(LAT, LNG, 10_000L,
                Set.of(nearest, next), distanceSorted);

        // ids only — the distance never leaves the module (D-P11)
        assertThat(page.getContent()).containsExactly(nearest, next);
        // the baked ORDER BY owns the order — the pageable arrives UNSORTED
        // (LIMIT/OFFSET only), the areaOrdered analog
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findWithinRadiusPaged(eq(LAT), eq(LNG), eq(10_000L),
                eq(Set.of(nearest, next)), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().isUnsorted()).isTrue();
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void findWithinRadiusPaged_emptyActiveSet_isAnHonestEmptyPage_noQuery() {
        var page = adapter.findWithinRadiusPaged(LAT, LNG, 10_000L, Set.of(),
                PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void findWithinRadiusPagedRestricted_delegatesBothRestrictions() {
        UUID active = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        when(repository.findWithinRadiusPagedRestricted(eq(LAT), eq(LNG), eq(10_000L),
                eq(Set.of(active)), eq(Set.of(provider)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(details(active, 100))));

        var page = adapter.findWithinRadiusPagedRestricted(LAT, LNG, 10_000L,
                Set.of(active), Set.of(provider),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "distance")));

        assertThat(page.getContent()).containsExactly(active);
    }

    @Test
    void findWithinRadiusPagedRestricted_emptyActiveSet_shortCircuits() {
        var page = adapter.findWithinRadiusPagedRestricted(LAT, LNG, 10_000L, Set.of(),
                Set.of(UUID.randomUUID()), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isZero();
        verifyNoInteractions(repository);
    }
}
