package com.marketplace.search;

import com.marketplace.shared.api.SearchCriteria;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * L27 (feature-expansion roadmap §5): the dedicated cache key generator for
 * the criteria path of {@code search-results-v3} (the reviewer's Major
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
 * first-class segments, which is the roadmap's "extend the search-results-v3
 * key with the window".
 *
 * <p>I6 (internal free plan §6): the guests criterion rides as a first-class
 * segment too — two searches differing only in guests never share a cached
 * entry. The PREFIX is bumped {@code l27v1 → l27v2} because the key schema
 * itself gained a component: l27v1 keys (9 segments) and l27v2 keys (10
 * segments) are disjoint by prefix, so no pre-change entry can be read as a
 * post-change hit — the one-time cold cycle is bounded by the 1h TTL.
 *
 * <p>L32 (realestate systems plan §5): the six real-estate facet criteria
 * ride as first-class segments (locationId, purpose, propertyType,
 * minRooms, minBathrooms, minAreaM2) and the PREFIX bumps
 * {@code l27v2 → l32v1} for the same reason — the key schema gained
 * components, so the spaces are disjoint by prefix and no pre-L32 entry
 * can be read as an L32 hit. (The cache NAME also bumps to
 * {@code search-results-v3} — see SearchService — the deploy-time eviction
 * of the schema extension; both mechanisms are the documented D-E6
 * decision.)
 *
 * <p>P1 (postgis integration plan §D-P12): the radius triple rides as
 * first-class segments — the two coordinates (already scale-6 NORMALIZED
 * at construction, so equivalent centers share one entry) and the radius
 * as WHOLE METERS (the criteria's meter-granularity gate makes the value
 * exact — two radii equal in meters share one entry whatever their km
 * spelling). The PREFIX bumps {@code l32v1 → l34v1} and the cache NAME
 * bumps {@code search-results-v3 → v4} — the schema extension's disjoint
 * spaces, the same one-time cold cycle bounded by the 1h TTL.
 */
@Component("searchCriteriaKeyGenerator")
public class SearchCriteriaCacheKeyGenerator implements KeyGenerator {

    private static final String PREFIX = "l34v1";

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
        append(key, criteria.guests());
        // L32: the six real-estate facets — first-class segments
        append(key, criteria.locationId());
        append(key, criteria.purpose());
        append(key, criteria.propertyType());
        append(key, criteria.minRooms());
        append(key, criteria.minBathrooms());
        append(key, criteria.minAreaM2());
        // P1: the radius triple — canonical segments (the coordinates are
        // scale-6 normalized at construction; the radius rides as whole
        // meters — the exact ST_DWithin argument)
        append(key, criteria.latitude());
        append(key, criteria.longitude());
        append(key, criteria.radiusKm() == null
                ? null
                : criteria.radiusKm().movePointRight(3).longValueExact());
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
