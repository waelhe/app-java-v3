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
}
