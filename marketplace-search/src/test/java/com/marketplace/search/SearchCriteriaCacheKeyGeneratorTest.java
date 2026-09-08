package com.marketplace.search;

import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L27 (PR #256, CodeRabbit round 1): the criteria-path cache key must be
 * injective — the reviewer's exact collision example is the first test, and
 * the null-vs-"null" boundary plus the window/pageable segments are pinned
 * alongside it. The record's toString() was NOT injective (unescaped
 * component concatenation); the generator's length-prefixed segments are,
 * by construction.
 */
class SearchCriteriaCacheKeyGeneratorTest {

    private final SearchCriteriaCacheKeyGenerator generator = new SearchCriteriaCacheKeyGenerator();

    private static final Method SEARCH; // any method — the generator reads only params
    static {
        try {
            SEARCH = SearchService.class.getMethod("search", SearchCriteria.class,
                    org.springframework.data.domain.Pageable.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object key(SearchCriteria criteria, org.springframework.data.domain.Pageable pageable) {
        return generator.generate(null, SEARCH, criteria, pageable);
    }

    @Test
    void theReviewersCollisionExample_producesDistinctKeys() {
        // query="foo, category=bar" + category="baz"  vs
        // query="foo" + category="bar, category=baz" — identical record
        // toString()s, distinct criteria, MUST get distinct keys.
        SearchCriteria first = new SearchCriteria("foo, category=bar", "baz", null, null);
        SearchCriteria second = new SearchCriteria("foo", "bar, category=baz", null, null);

        assertThat(key(first, PageRequest.of(0, 10)))
                .isNotEqualTo(key(second, PageRequest.of(0, 10)));
    }

    @Test
    void nullComponent_neverCollidesWithTheLiteralString() {
        SearchCriteria nullQuery = new SearchCriteria(null, "cat", null, null);
        SearchCriteria literalNullQuery = new SearchCriteria("null", "cat", null, null);

        assertThat(key(nullQuery, PageRequest.of(0, 10)))
                .isNotEqualTo(key(literalNullQuery, PageRequest.of(0, 10)));

        // and the empty string is its own component, distinct from both.
        SearchCriteria emptyQuery = new SearchCriteria("", "cat", null, null);
        assertThat(key(nullQuery, PageRequest.of(0, 10)))
                .isNotEqualTo(key(emptyQuery, PageRequest.of(0, 10)));
    }

    @Test
    void differentWindows_neverShareAKey() {
        // The roadmap contract: the window rides the key — two windows over
        // otherwise identical criteria are different cache entries.
        SearchCriteria base = new SearchCriteria("loft", null, null, null,
                Instant.parse("2026-10-05T10:00:00Z"), Instant.parse("2026-10-08T10:00:00Z"));
        SearchCriteria laterWindow = new SearchCriteria("loft", null, null, null,
                Instant.parse("2026-10-12T10:00:00Z"), Instant.parse("2026-10-15T10:00:00Z"));

        assertThat(key(base, PageRequest.of(0, 10)))
                .isNotEqualTo(key(laterWindow, PageRequest.of(0, 10)));

        // windowless vs windowed — also distinct (no cross-contamination).
        SearchCriteria windowless = new SearchCriteria("loft", null, null, null);
        assertThat(key(base, PageRequest.of(0, 10)))
                .isNotEqualTo(key(windowless, PageRequest.of(0, 10)));
    }

    @Test
    void paginationSegments_rideTheKey() {
        SearchCriteria criteria = new SearchCriteria("loft", null, null, null);

        assertThat(key(criteria, PageRequest.of(0, 10)))
                .isNotEqualTo(key(criteria, PageRequest.of(1, 10)))
                .isNotEqualTo(key(criteria, PageRequest.of(0, 20)));
    }

    @Test
    void equalCriteria_produceTheSameKey_deterministically() {
        SearchCriteria first = new SearchCriteria("loft", "home",
                BigDecimal.valueOf(10), BigDecimal.valueOf(20),
                Instant.parse("2026-10-05T10:00:00Z"), Instant.parse("2026-10-08T10:00:00Z"));
        SearchCriteria second = new SearchCriteria("loft", "home",
                BigDecimal.valueOf(10), BigDecimal.valueOf(20),
                Instant.parse("2026-10-05T10:00:00Z"), Instant.parse("2026-10-08T10:00:00Z"));

        assertThat(key(first, PageRequest.of(0, 10)))
                .isEqualTo(key(second, PageRequest.of(0, 10)));
    }

    @Test
    void foreignParameters_failFastWithTheHonestSignature() {
        assertThatThrownBy(() -> generator.generate(null, SEARCH, "not a criteria", PageRequest.of(0, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SearchCriteria");
    }
}
