package com.marketplace.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PagedResponseTest {

    @Test
    void of_createsFromPage() {
        var content = List.of("item1", "item2");
        var pageable = PageRequest.of(0, 10);
        var page = new PageImpl<>(content, pageable, 25);

        var result = PagedResponse.of(page);

        assertThat(result.content()).isEqualTo(content);
        assertThat(result.pageNumber()).isZero();
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(25);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.last()).isFalse();
    }

    @Test
    void empty_carriesTheRequestsMetadataWithZeroTotalAndLast() {
        var result = PagedResponse.empty(PagedRequest.of(2, 10));

        assertThat(result.content()).isEmpty();
        assertThat(result.pageNumber()).isEqualTo(2);
        assertThat(result.pageSize()).isEqualTo(10);
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.last()).isTrue();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void map_mapsTheContentAndKeepsThePageMetadata() {
        var page = PagedResponse.of(
                new PageImpl<>(List.of("one", "two", "three"), PageRequest.of(0, 3), 8));

        var mapped = page.map(s -> s.length());

        assertThat(mapped.content()).containsExactly(3, 3, 5);
        assertThat(mapped.pageNumber()).isZero();
        assertThat(mapped.pageSize()).isEqualTo(3);
        assertThat(mapped.totalElements()).isEqualTo(8);
        assertThat(mapped.totalPages()).isEqualTo(3);
        assertThat(mapped.last()).isFalse();
        assertThat(mapped.isEmpty()).isFalse();
    }
}
