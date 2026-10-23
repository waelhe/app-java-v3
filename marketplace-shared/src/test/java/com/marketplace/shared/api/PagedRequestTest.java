package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The framework-neutral pagination request's own contract — the
 * unsorted browse form, the sorted request with its ordering steps in
 * priority order, and the {@code isSorted} probe the adapters branch on
 * (the port signatures that keep Spring Data INSIDE the adapters).
 */
class PagedRequestTest {

    @Test
    void theUnsortedBrowseFormCarriesNoOrderingSteps() {
        PagedRequest request = PagedRequest.of(2, 20);
        assertThat(request.page()).isEqualTo(2);
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.sort()).isEmpty();
        assertThat(request.isSorted()).isFalse();
    }

    @Test
    void theSortedFormCarriesItsStepsInPriorityOrder() {
        PagedRequest.Order price = new PagedRequest.Order("priceCents", true);
        PagedRequest.Order idTiebreak = new PagedRequest.Order("id", false);
        PagedRequest request = PagedRequest.of(0, 10, price, idTiebreak);

        assertThat(request.isSorted()).isTrue();
        assertThat(request.sort()).containsExactly(price, idTiebreak);
        assertThat(request.sort().get(0).property()).isEqualTo("priceCents");
        assertThat(request.sort().get(0).descending()).isTrue();
        assertThat(request.sort().get(1).property()).isEqualTo("id");
        assertThat(request.sort().get(1).descending()).isFalse();
    }

    @Test
    void theCanonicalConstructorAcceptsItsOwnOrderingList() {
        List<PagedRequest.Order> orders = List.of(new PagedRequest.Order("area", true));
        PagedRequest request = new PagedRequest(1, 5, orders);
        assertThat(request.page()).isEqualTo(1);
        assertThat(request.size()).isEqualTo(5);
        assertThat(request.sort()).isEqualTo(orders);
        assertThat(request.isSorted()).isTrue();
    }
}
