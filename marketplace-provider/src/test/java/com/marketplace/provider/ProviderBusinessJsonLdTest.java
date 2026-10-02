package com.marketplace.provider;

import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PublishedReviewView;
import com.marketplace.shared.api.RatingDistribution;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2 (yelp-level plan §5 — the business page): the LocalBusiness JSON-LD
 * block's unit guards — the wave's own acceptance criterion
 * («أداة فحص البيانات المنظمة ترى النجوم»): the stars appear exactly when
 * published reviews exist, never zeroed; the openingHours entries follow
 * the schema.org canonical form; the review sample is bounded and carries
 * the pseudonym-honouring author name; and the omission rules (url, bio,
 * areaServed) keep the block honest.
 */
class ProviderBusinessJsonLdTest {

    private static final UUID PROVIDER_ID = UUID.randomUUID();

    private static ProviderProfile profile() {
        return ProviderProfile.create("شركة النور للخدمات", "تنظيف وصيانة عامة", UUID.randomUUID());
    }

    private static List<BusinessHour> hours(DayOfWeek day, String opens, String closes) {
        return List.of(BusinessHour.create(PROVIDER_ID, day,
                LocalTime.parse(opens), LocalTime.parse(closes)));
    }

    private static PublishedReviewView review(String comment, Instant createdAt) {
        return new PublishedReviewView(UUID.randomUUID(), 5, comment, null, null,
                createdAt, "ORGANIC", UUID.randomUUID(), "محمد أ.", 12L, 3L);
    }

    @Test
    void aggregateRating_appearsOnlyWhenPublishedReviewsExist_neverZeroed() {
        // Zero count → the whole aggregateRating (and review) block omitted —
        // the plan's literal rule: «يُبثان فقط عند وجود مراجعات مقبولة».
        ProviderBusinessJsonLd noReviews = ProviderBusinessJsonLd.of(
                profile(), List.of(), List.of(), Optional.empty(), List.of(), Optional.empty());
        assertThat(noReviews.aggregateRating()).isNull();
        assertThat(noReviews.review()).isNull();

        // Published reviews → the aggregate rides with the full schema.org shape.
        ProviderBusinessJsonLd withReviews = ProviderBusinessJsonLd.of(
                profile(), List.of(), List.of(),
                ProviderBusinessJsonLd.aggregateOf(4.5, 23L),
                List.of(review("خدمة ممتازة", Instant.parse("2026-09-01T10:00:00Z"))),
                Optional.empty());
        assertThat(withReviews.aggregateRating()).isNotNull();
        assertThat(withReviews.aggregateRating().ratingValue()).isEqualTo(4.5);
        assertThat(withReviews.aggregateRating().reviewCount()).isEqualTo(23L);
        assertThat(withReviews.aggregateRating().bestRating()).isEqualTo(5);
        assertThat(withReviews.aggregateRating().worstRating()).isEqualTo(1);
        assertThat(withReviews.aggregateRating().type()).isEqualTo("AggregateRating");
    }

    @Test
    void aggregateOf_zeroOrNullCount_isEmpty() {
        assertThat(ProviderBusinessJsonLd.aggregateOf(null, 0L)).isEmpty();
        assertThat(ProviderBusinessJsonLd.aggregateOf(4.5, 0L)).isEmpty();
        assertThat(ProviderBusinessJsonLd.aggregateOf(4.5, 23L)).isPresent();
    }

    @Test
    void openingHours_followTheSchemaOrgCanonicalForm() {
        ProviderBusinessJsonLd block = ProviderBusinessJsonLd.of(
                profile(),
                hours(DayOfWeek.MONDAY, "09:00", "17:00"),
                List.of(), Optional.empty(), List.of(), Optional.empty());

        assertThat(block.openingHours()).containsExactly("Mo 09:00-17:00");
        assertThat(block.type()).isEqualTo("LocalBusiness");
        assertThat(block.context()).isEqualTo("https://schema.org");
        assertThat(block.name()).isEqualTo("شركة النور للخدمات");
        assertThat(block.description()).isEqualTo("تنظيف وصيانة عامة");
    }

    @Test
    void openingHours_mapsEveryIsoWeekdayToItsTwoLetterAbbreviation() {
        List<String> entries = ProviderBusinessJsonLd.openingHoursOf(List.of(
                BusinessHour.create(PROVIDER_ID, DayOfWeek.TUESDAY,
                        LocalTime.parse("08:30"), LocalTime.parse("12:00")),
                BusinessHour.create(PROVIDER_ID, DayOfWeek.SATURDAY,
                        LocalTime.parse("10:00"), LocalTime.parse("14:30"))));
        assertThat(entries).containsExactly("Tu 08:30-12:00", "Sa 10:00-14:30");
    }

    @Test
    void omittedCapabilities_omitTheirFields_neverFabricate() {
        ProviderBusinessJsonLd block = ProviderBusinessJsonLd.of(
                profile(), List.of(), List.of(), Optional.empty(), List.of(),
                Optional.empty());
        assertThat(block.url()).isNull();
        assertThat(block.openingHours()).isNull();
        assertThat(block.areaServed()).isNull();
    }

    @Test
    void areaServed_carriesTheResolvedPlaceNames() {
        ProviderBusinessJsonLd block = ProviderBusinessJsonLd.of(
                profile(), List.of(), List.of("الرياض"), Optional.empty(), List.of(),
                Optional.of("https://public.example/providers/" + PROVIDER_ID));

        assertThat(block.areaServed()).hasSize(1);
        assertThat(block.areaServed().get(0).name()).isEqualTo("الرياض");
        assertThat(block.url()).isEqualTo("https://public.example/providers/" + PROVIDER_ID);
    }

    @Test
    void reviewSample_isBoundedAndCarriesTheAuthorAndRating() {
        List<PublishedReviewView> many = java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(i -> review("تعليق " + i, Instant.parse("2026-09-0" + i + "T10:00:00Z")))
                .toList();

        ProviderBusinessJsonLd block = ProviderBusinessJsonLd.of(
                profile(), List.of(), List.of(),
                ProviderBusinessJsonLd.aggregateOf(4.5, 23L), many, Optional.empty());

        assertThat(block.review()).hasSize(ProviderBusinessJsonLd.MAX_REVIEWS_IN_LD);
        ProviderBusinessJsonLd.Review first = block.review().get(0);
        assertThat(first.type()).isEqualTo("Review");
        assertThat(first.author().name()).isEqualTo("محمد أ.");
        assertThat(first.author().type()).isEqualTo("Person");
        assertThat(first.reviewRating().ratingValue()).isEqualTo(5);
        assertThat(first.datePublished()).isEqualTo("2026-09-01");
        assertThat(first.reviewBody()).isEqualTo("تعليق 1");
    }

    @Test
    void nullComment_isOmittedFromTheReviewBody() {
        PublishedReviewView silent = new PublishedReviewView(UUID.randomUUID(), 4, null,
                null, null, Instant.parse("2026-09-02T10:00:00Z"), "BOOKING", UUID.randomUUID(), "سارة", 3L, 0L);
        ProviderBusinessJsonLd block = ProviderBusinessJsonLd.of(
                profile(), List.of(), List.of(),
                ProviderBusinessJsonLd.aggregateOf(4.2, 3L), List.of(silent), Optional.empty());

        assertThat(block.review()).hasSize(1);
        assertThat(block.review().get(0).reviewBody()).isNull();
    }
}
