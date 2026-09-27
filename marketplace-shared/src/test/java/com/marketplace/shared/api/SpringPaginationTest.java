package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The interop corner's round-trip contracts — the exact fidelity the
 * neutral ports rely on (the adapters convert at the boundary and the
 * repositories see the same page/size/sort the caller sent). Every
 * ordering vocabulary that measurably crosses the ports is covered:
 * the mapped price/newest orders with the id tiebreak, and the
 * area/distance markers.
 */
class SpringPaginationTest {

    @Test
    void toPagedRequest_unsortedPageable_mapsToTheUnsortedRequest() {
        var request = SpringPagination.toPagedRequest(PageRequest.of(3, 20));

        assertThat(request.page()).isEqualTo(3);
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.isSorted()).isFalse();
        assertThat(request.sort()).isEmpty();
    }

    @Test
    void toPagedRequest_sortedPageable_carriesEveryOrderStep() {
        var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "priceCents")
                .and(Sort.by(Sort.Direction.ASC, "id")));

        var request = SpringPagination.toPagedRequest(pageable);

        assertThat(request.sort()).containsExactly(
                new PagedRequest.Order("priceCents", true),
                new PagedRequest.Order("id", false));
    }

    @Test
    void toPageable_roundTripsTheSortedRequestWithDirectionsIntact() {
        var original = PageRequest.of(2, 5, Sort.by(Sort.Direction.ASC, "area")
                .and(Sort.by(Sort.Direction.DESC, "createdAt")));

        var roundTripped = SpringPagination.toPageable(
                SpringPagination.toPagedRequest(original));

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void toPageable_unsortedRequest_mapsToTheUnsortedPageable() {
        var pageable = SpringPagination.toPageable(PagedRequest.of(4, 15));

        assertThat(pageable.getPageNumber()).isEqualTo(4);
        assertThat(pageable.getPageSize()).isEqualTo(15);
        assertThat(pageable.getSort().isUnsorted()).isTrue();
    }

    @Test
    void toPageable_sortedRequest_carriesTheOrderingSteps() {
        var pageable = SpringPagination.toPageable(
                PagedRequest.of(0, 10,
                        new PagedRequest.Order("distance", false),
                        new PagedRequest.Order("id", false)));

        assertThat(pageable.getSort().toString()).isEqualTo("distance: ASC,id: ASC");
    }

    @Test
    void toPage_contentAndTotalComeFromTheResponse_metadataFromTheOriginal() {
        var original = PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "area"));
        var response = PagedResponse.of(
                new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 7));

        var page = SpringPagination.toPage(response, original);

        assertThat(page.getContent()).containsExactly("a", "b");
        assertThat(page.getTotalElements()).isEqualTo(7);
        assertThat(page.getPageable()).isEqualTo(original);
    }
}
