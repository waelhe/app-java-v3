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

    // ---- I6: the guests segment -----------------------------------------------

    @Test
    void differentGuests_neverShareAKey() {
        // The I6 contract: the guests criterion rides the key — two searches
        // differing only in guests count are different cache entries (an
        // entry cached for guests=2 must never serve guests=5).
        SearchCriteria two = new SearchCriteria("loft", null, null, null, null, null, 2);
        SearchCriteria five = new SearchCriteria("loft", null, null, null, null, null, 5);

        assertThat(key(two, PageRequest.of(0, 10)))
                .isNotEqualTo(key(five, PageRequest.of(0, 10)));
    }

    @Test
    void guestsNull_neverCollidesWithGuestsOne_orTheLiteralString() {
        // Criterion-less (null) vs criterion=1 — distinct entries (the
        // legacy behavior is byte-identical only because null rides its own
        // segment, never borrowed from a neighboring component).
        SearchCriteria noGuests = new SearchCriteria("loft", null, null, null);
        SearchCriteria oneGuest = new SearchCriteria("loft", null, null, null, null, null, 1);

        assertThat(key(noGuests, PageRequest.of(0, 10)))
                .isNotEqualTo(key(oneGuest, PageRequest.of(0, 10)));

        // The prefix bump (l27v2 → l32v1 → l34v1 with the P1 radius schema)
        // means no pre-P1 entry can be read: the new schema carries the
        // radius segments, old keys do not.
        assertThat(key(noGuests, PageRequest.of(0, 10)).toString()).startsWith("l34v1|");
    }

    // ---- L32: the six real-estate facets ride as first-class segments ----

    @Test
    void propertyFacets_neverShareEntriesWithFacetlessCriteria() {
        SearchCriteria faceted = new SearchCriteria(null, null, null, null, null, null, null,
                java.util.UUID.randomUUID(),
                com.marketplace.shared.api.PropertyPurpose.RENT,
                com.marketplace.shared.api.PropertyType.APARTMENT, 2, 1, 80, null, null, null);
        SearchCriteria facetless = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        assertThat(key(faceted, PageRequest.of(0, 10)))
                .isNotEqualTo(key(facetless, PageRequest.of(0, 10)));
        // differing in a single facet only — still disjoint
        SearchCriteria otherRooms = new SearchCriteria(null, null, null, null, null, null, null,
                faceted.locationId(), faceted.purpose(), faceted.propertyType(), 3, 1, 80, null, null, null);
        assertThat(key(faceted, PageRequest.of(0, 10)))
                .isNotEqualTo(key(otherRooms, PageRequest.of(0, 10)));
    }

    // ---- P1 (postgis plan §D-P12): the radius triple rides as canonical segments ----

    private static final BigDecimal LAT = new BigDecimal("33.558889");
    private static final BigDecimal LNG = new BigDecimal("36.056944");

    @Test
    void radiusAndFacetlessCriteria_neverShareEntries() {
        SearchCriteria withRadius = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, LAT, LNG, new BigDecimal("10"));
        SearchCriteria radiusless = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        assertThat(key(withRadius, PageRequest.of(0, 10)))
                .isNotEqualTo(key(radiusless, PageRequest.of(0, 10)));
    }

    @Test
    void differentRadii_neverShareAKey() {
        SearchCriteria ten = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, LAT, LNG, new BigDecimal("10"));
        SearchCriteria twenty = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, LAT, LNG, new BigDecimal("20"));

        assertThat(key(ten, PageRequest.of(0, 10)))
                .isNotEqualTo(key(twenty, PageRequest.of(0, 10)));
    }

    @Test
    void differentCenters_neverShareAKey() {
        SearchCriteria here = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, LAT, LNG, new BigDecimal("10"));
        SearchCriteria elsewhere = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                new BigDecimal("33.500000"), LNG, new BigDecimal("10"));

        assertThat(key(here, PageRequest.of(0, 10)))
                .isNotEqualTo(key(elsewhere, PageRequest.of(0, 10)));
    }

    @Test
    void equivalentCenters_shareOneEntry_canonicalScale6() {
        // The center is normalized to scale-6 at construction: 33.558889 and
        // 33.5588890 are the SAME effective center (V48's NUMERIC(9,6) scale)
        // — one cache entry, not a split cache (the plan's D-P12 canonical
        // segments).
        SearchCriteria plain = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, LAT, LNG, new BigDecimal("10"));
        SearchCriteria padded = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                new BigDecimal("33.5588890"), new BigDecimal("36.0569440"), new BigDecimal("10"));

        assertThat(key(plain, PageRequest.of(0, 10)))
                .isEqualTo(key(padded, PageRequest.of(0, 10)));
    }

    @Test
    void equivalentRadiiInMeters_shareOneEntry_wholeMeterCanonical() {
        // 10 km and 10.000 km are the same 10000 meters — the radius rides
        // the key as WHOLE METERS, so equivalent spellings share one entry
        // (the plan's D-P12: "radius in whole meters").
        SearchCriteria whole = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, LAT, LNG, new BigDecimal("10"));
        SearchCriteria padded = new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, LAT, LNG, new BigDecimal("10.000"));

        assertThat(key(whole, PageRequest.of(0, 10)))
                .isEqualTo(key(padded, PageRequest.of(0, 10)));
    }

    @Test
    void thePrefixBumpedToL34v1_theSchemaSpacesAreDisjoint() {
        // The P1 prefix bump (l32v1 → l34v1): no pre-P1 entry can be read as
        // a post-P1 hit — the radius segments exist only in the new space.
        SearchCriteria any = new SearchCriteria("loft", null, null, null);

        assertThat(key(any, PageRequest.of(0, 10)).toString()).startsWith("l34v1|");
    }
}
