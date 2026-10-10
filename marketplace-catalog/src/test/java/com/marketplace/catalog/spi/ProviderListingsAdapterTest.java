package com.marketplace.catalog.spi;

import com.marketplace.catalog.ListingStatus;
import com.marketplace.catalog.ProviderListing;
import com.marketplace.catalog.ProviderListingRepository;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ProviderListingSummary;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JT-20 (#536 discovery waves D1-D4): the provider-listings adapter's
 * contract — the eligibility floor (ACTIVE only, the storefront's own
 * state), the rail's complete deterministic order stamped by the adapter
 * (createdAt DESC, id DESC — no caller sort), the honest-empty provider
 * set that never reaches the database, and the verbatim row mapping
 * (price + currency travel together, S7).
 */
class ProviderListingsAdapterTest {

    private final ProviderListingRepository repository = mock(ProviderListingRepository.class);
    private final ProviderListingsAdapter adapter = new ProviderListingsAdapter(repository);

    @Test
    void gatesOnActivePinsNewestFirstOrderAndMapsVerbatim() {
        ProviderListing listing = Instancio.of(ProviderListing.class)
                .set(field(ProviderListing::getStatus), ListingStatus.ACTIVE)
                .set(field(ProviderListing::getPriceCents), 125_000L)
                .set(field(ProviderListing::getCurrency), "SAR")
                .create();

        when(repository.findByProviderIdInAndStatus(any(), eq(ListingStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(listing)));

        PagedResponse<ProviderListingSummary> response = adapter.findActiveByProviders(
                Set.of(listing.getProviderId()), new PagedRequest(0, 20, List.of()));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findByProviderIdInAndStatus(
                org.mockito.ArgumentMatchers.<Set<UUID>>any(), eq(ListingStatus.ACTIVE), pageable.capture());
        Sort sort = pageable.getValue().getSort();
        assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);

        assertThat(response.content()).hasSize(1);
        ProviderListingSummary summary = response.content().get(0);
        assertThat(summary.id()).isEqualTo(listing.getId());
        assertThat(summary.title()).isEqualTo(listing.getTitle());
        assertThat(summary.category()).isEqualTo(listing.getCategory());
        assertThat(summary.price()).isEqualByComparingTo(new java.math.BigDecimal("1250.00"));
        assertThat(summary.price().scale()).isEqualTo(2);
        assertThat(summary.currency()).isEqualTo("SAR");
        assertThat(summary.providerId()).isEqualTo(listing.getProviderId());
        assertThat(summary.status()).isEqualTo("ACTIVE");
        assertThat(summary.createdAt()).isEqualTo(listing.getCreatedAt());
        assertThat(summary.updatedAt()).isEqualTo(listing.getUpdatedAt());

        assertThat(response.pageNumber()).isZero();
        assertThat(response.pageSize()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void emptyProviderSetAnswersEmptyPageWithoutQuery() {
        PagedResponse<ProviderListingSummary> response =
                adapter.findActiveByProviders(Set.of(), new PagedRequest(2, 10, List.of()));

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.pageNumber()).isEqualTo(2);
        assertThat(response.pageSize()).isEqualTo(10);
        verifyNoInteractions(repository);
    }

    @Test
    void nullProviderSetAnswersEmptyPageWithoutQuery() {
        PagedResponse<ProviderListingSummary> response =
                adapter.findActiveByProviders(null, new PagedRequest(0, 10, List.of()));

        assertThat(response.content()).isEmpty();
        verifyNoInteractions(repository);
    }
}
