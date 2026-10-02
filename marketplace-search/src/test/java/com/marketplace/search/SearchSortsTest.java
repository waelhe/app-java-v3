package com.marketplace.search;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W3 (yelp-level plan §5 — G16): the whitelist's rating sort — the
 * mapping and the loud boundary, pinned at the unit level (the L32
 * whitelist's own discipline: every supported property has a mapped form
 * and every unsupported direction answers 400 before any query).
 */
class SearchSortsTest {

    @Test
    void ratingSort_mapsHighestFirstOntoTheCompositeColumn() {
        var normalized = SearchSorts.normalize(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "rating")));

        assertThat(normalized.getSort().stream().map(Sort.Order::getProperty))
                .containsExactly("rankingScore", "id");
        assertThat(normalized.getSort().getOrderFor("rankingScore").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(normalized.getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void ratingSort_ascendingIsTheLoudBoundary() {
        assertThatThrownBy(() -> SearchSorts.normalize(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "rating"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("sort=rating orders highest-first");
    }

    @Test
    void ratingSort_composesWithAnotherMappedProperty() {
        // rating is a MAPPED property (not a flow marker like area/distance):
        // a multi-property request composes — the composite first, the price
        // second, the id tiebreak always.
        var normalized = SearchSorts.normalize(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "rating", "price")));

        assertThat(normalized.getSort().stream().map(Sort.Order::getProperty))
                .containsExactly("rankingScore", "priceCents", "id");
    }

    @Test
    void unsupportedSortMessageNamesRatingAmongTheSupportedSet() {
        assertThatThrownBy(() -> SearchSorts.normalize(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "bogus"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("price, newest, rating, area, distance");
    }

    @Test
    void unsortedPageablePassesThroughUnchanged() {
        var pageable = PageRequest.of(3, 20);
        assertThat(SearchSorts.normalize(pageable)).isEqualTo(pageable);
    }
}
