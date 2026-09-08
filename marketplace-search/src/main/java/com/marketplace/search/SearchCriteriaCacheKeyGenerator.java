package com.marketplace.search;

import com.marketplace.shared.api.SearchCriteria;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * L27 (feature-expansion roadmap §5): the dedicated cache key generator for
 * the criteria path of {@code search-results-v2} (the reviewer's Major
 * finding, PR #256 round 1): the record's {@code toString()} concatenates
 * component values without escaping, so two DIFFERENT criteria can produce
 * the SAME string — e.g. {@code query="foo, category=bar"} +
 * {@code category="baz"} is byte-identical to {@code query="foo"} +
 * {@code category="bar, category=baz"} — and one request could be served
 * the other's cached page.
 *
 * <p>This key is <em>injective by construction</em>: every component rides
 * as a length-prefixed segment {@code |<len>:<repr>} (null = a bare
 * separator). The authoritative length makes forged component boundaries
 * impossible — two distinct component tuples cannot produce the same key,
 * whatever the user types. The window components (checkIn/checkOut) are
 * first-class segments, which is the roadmap's "extend the search-results-v2
 * key with the window".
 */
@Component("searchCriteriaKeyGenerator")
public class SearchCriteriaCacheKeyGenerator implements KeyGenerator {

    private static final String PREFIX = "l27v1";

    @Override
    public Object generate(Object target, Method method, Object... params) {
        if (params.length != 2
                || !(params[0] instanceof SearchCriteria criteria)
                || !(params[1] instanceof Pageable pageable)) {
            throw new IllegalArgumentException(
                    "searchCriteriaKeyGenerator expects (SearchCriteria, Pageable) — got "
                            + java.util.Arrays.toString(params));
        }
        StringBuilder key = new StringBuilder(PREFIX);
        append(key, criteria.query());
        append(key, criteria.category());
        append(key, criteria.minPrice());
        append(key, criteria.maxPrice());
        append(key, criteria.checkIn());
        append(key, criteria.checkOut());
        append(key, pageable.getPageNumber());
        append(key, pageable.getPageSize());
        append(key, pageable.getSort());
        return key.toString();
    }

    /**
     * Length-prefixed segment: {@code |<len>:<repr>} for a value, a bare
     * {@code |} for null. Injective per component — the length is
     * authoritative when reading, never the delimiters inside the value.
     */
    private static void append(StringBuilder key, Object value) {
        if (value == null) {
            key.append('|');
            return;
        }
        String repr = value.toString();
        key.append('|').append(repr.length()).append(':').append(repr);
    }
}
