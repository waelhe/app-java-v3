package com.marketplace.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.marketplace.shared.api.PublishedReviewView;

import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * W2 (yelp-level plan §5 — the business page): the schema.org
 * {@code LocalBusiness} JSON-LD block the public provider page gains —
 * the structured-data surface that makes «أداة فحص البيانات المنظمة ترى
 * النجوم» (the wave's acceptance criterion) true.
 *
 * <p><b>Every field is a measured schema.org fact (the L39 discipline —
 * the listing twin {@code RealEstateListingJsonLd} established it):</b>
 * <ul>
 *   <li>{@code LocalBusiness} (Thing &gt; Organization &gt; LocalBusiness)
 *       — the type of the page's subject: a local service business. The
 *       Yelp business page's own structured-data shape.</li>
 *   <li>{@code name}/{@code description} — Thing properties, from the
 *       profile's display name and bio (bio omitted when absent — never
 *       fabricated).</li>
 *   <li>{@code url} — the page's own public URL, present only when the
 *       public-site origin is bound ({@code ProviderProperties.Seo} — the
 *       L39 honesty rule: a fabricated URL helps no one).</li>
 *   <li>{@code openingHours} — the schema.org time format the plan names
 *       («وعرضها في openingHours النمطية»): one entry per declared day,
 *       {@code "Mo 09:00-17:00"} — the two-letter weekday abbreviations
 *       and 24-hour times the property's own documentation prescribes.
 *       Present only when the provider declares hours (an absent block is
 *       omitted, not zeroed).</li>
 *   <li>{@code areaServed} — the declared service areas as
 *       {@code Place} entries carrying the geo node's Arabic name (the
 *       same display language the listing JSON-LD's
 *       {@code PostalAddress} fields use — one convention for the whole
 *       structured-data surface). Present only when areas are declared.</li>
 *   <li>{@code aggregateRating} — an {@code AggregateRating} with
 *       {@code ratingValue}, {@code reviewCount}, {@code bestRating: 5},
 *       {@code worstRating: 1}. <b>Emitted only when published reviews
 *       exist</b> — the plan's literal rule («يُبثان فقط عند وجود مراجعات
 *       مقبولة»): a zero-count aggregate is omitted, never zeroed
 *       (schema.org's own guidance: the aggregate describes actual
 *       ratings; an empty one is invalid markup, not an honest zero).</li>
 *   <li>{@code review} — a bounded sample of the provider's PUBLISHED
 *       reviews ({@value #MAX_REVIEWS_IN_LD} leading rows of the
 *       population the mode-driven {@code aggregateRating} beside it
 *       describes — the booking-origin population in VERIFIED_ONLY/HYBRID,
 *       the merged population in OPEN; newest first, the complete
 *       ordering key): each a
 *       {@code Review} carrying {@code reviewRating},
 *       {@code datePublished} (the row's own createdAt), {@code author}
 *       (the pseudonym-honouring display name the projection already
 *       resolved — never an email, the W1 r5/r6 root fix) and
 *       {@code reviewBody} (the comment, omitted when null). Emitted
 *       under the same only-when-reviews-exist rule.</li>
 * </ul>
 *
 * <p><b>The mode-driven aggregate (the §4.4 display law, one level up):</b>
 * VERIFIED_ONLY → the verified aggregate; OPEN → the plan's merged single
 * number (the owner's explicit merge decision); HYBRID → the VERIFIED
 * aggregate — merging in HYBRID is exactly what §4.4 forbids («الإشارة
 * الأقلى يجب أن تبقى مرئية» / the two badges never merge), and the
 * verified pair is the trust signal the mode itself leads with. One
 * {@code aggregateRating} element per item is the schema.org shape; the
 * page's own rating block still renders both badges.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderBusinessJsonLd(
        @JsonProperty("@context") String context,
        @JsonProperty("@type") String type,
        String name,
        String description,
        String url,
        List<String> openingHours,
        List<Place> areaServed,
        AggregateRating aggregateRating,
        List<Review> review
) {

    public static final String SCHEMA_CONTEXT = "https://schema.org";
    public static final String SCHEMA_TYPE = "LocalBusiness";

    /** The bounded review sample — the rich-results convention (a handful, never the whole page). */
    static final int MAX_REVIEWS_IN_LD = 5;

    /** schema.org's two-letter weekday abbreviations, ISO order (1=Monday). */
    private static final List<String> WEEKDAY_ABBREVIATIONS =
            List.of("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su");

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    /**
     * The block for one public business page.
     *
     * @param profile      the page's provider profile (name/bio source)
     * @param hours        the declared working hours (may be empty — the field is omitted)
     * @param areaNames    the declared service areas' display names (may be empty — omitted)
     * @param rating       the mode-driven aggregate — average and count; empty when
     *                     no published reviews exist (aggregateRating AND review omitted)
     * @param reviews      the page's own reviews block rows (the sample takes its
     *                     leading {@value #MAX_REVIEWS_IN_LD} rows)
     * @param pageUrl      the page's public URL — empty when the SEO capability is OFF
     */
    public static ProviderBusinessJsonLd of(ProviderProfile profile,
                                            List<BusinessHour> hours,
                                            List<String> areaNames,
                                            Optional<AggregateRating> rating,
                                            List<PublishedReviewView> reviews,
                                            Optional<String> pageUrl) {
        boolean hasReviews = rating.isPresent() && rating.orElseThrow().reviewCount() > 0;
        List<Review> reviewSample = hasReviews
                ? reviews.stream().limit(MAX_REVIEWS_IN_LD).map(Review::of).toList()
                : null;
        return new ProviderBusinessJsonLd(
                SCHEMA_CONTEXT,
                SCHEMA_TYPE,
                profile.getDisplayName(),
                profile.getBio(),
                pageUrl.orElse(null),
                openingHoursOf(hours),
                areaNames.isEmpty() ? null : areaNames.stream().map(Place::new).toList(),
                hasReviews ? rating.orElseThrow() : null,
                reviewSample);
    }

    /**
     * The canonical {@code openingHours} entries — one per declared day,
     * {@code "Mo 09:00-17:00"}: the two-letter abbreviation of the ISO
     * weekday and the 24-hour window (schema.org's own prescribed format).
     * Null when no hours are declared.
     */
    static List<String> openingHoursOf(List<BusinessHour> hours) {
        if (hours.isEmpty()) {
            return null;
        }
        List<String> entries = new ArrayList<>(hours.size());
        for (BusinessHour hour : hours) {
            DayOfWeek day = hour.getDayOfWeek();
            entries.add(WEEKDAY_ABBREVIATIONS.get(day.getValue() - 1)
                    + " " + TIME_FORMAT.format(hour.getOpensAt())
                    + "-" + TIME_FORMAT.format(hour.getClosesAt()));
        }
        return List.copyOf(entries);
    }

    /**
     * The {@code AggregateRating} — the mode-driven pair the page's rating
     * block already resolved (the same numbers the visible badges render;
     * a rich result that disagrees with its own page is invalid markup).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AggregateRating(
            @JsonProperty("@type") String type,
            Double ratingValue,
            long reviewCount,
            int bestRating,
            int worstRating
    ) {
        static final String AGGREGATE_TYPE = "AggregateRating";

        public static AggregateRating of(Double ratingValue, long reviewCount) {
            return new AggregateRating(AGGREGATE_TYPE, ratingValue, reviewCount, 5, 1);
        }
    }

    /**
     * The {@code Review} sample row — the rating, the publication instant,
     * the pseudonym-honouring author name and the comment (omitted when
     * the row carries none).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Review(
            @JsonProperty("@type") String type,
            ReviewRating reviewRating,
            String datePublished,
            Author author,
            String reviewBody
    ) {
        static final String REVIEW_TYPE = "Review";
        private static final DateTimeFormatter DATE_FORMAT =
                DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneOffset.UTC);

        static Review of(PublishedReviewView row) {
            return new Review(REVIEW_TYPE,
                    new ReviewRating("Rating", row.rating()),
                    row.createdAt() == null ? null : DATE_FORMAT.format(row.createdAt()),
                    new Author("Person", row.reviewerName()),
                    row.comment());
        }
    }

    public record ReviewRating(
            @JsonProperty("@type") String type,
            Integer ratingValue
    ) {
    }

    public record Author(
            @JsonProperty("@type") String type,
            String name
    ) {
    }

    /**
     * A declared service area as a {@code Place} — the geo node's resolved
     * display name (the same Arabic-name convention the listing JSON-LD's
     * address fields apply).
     */
    public record Place(String name) {
    }

    /**
     * Utility for the page service: the histogram's zero-bucketed entries
     * collapsed to the distribution's own average/count pair — the derived
     * {@link AggregateRating} input when the caller starts from a
     * distribution (used by the merged OPEN path).
     */
    static Optional<AggregateRating> aggregateOf(Double average, long count) {
        if (count <= 0 || average == null) {
            return Optional.empty();
        }
        return Optional.of(AggregateRating.of(average, count));
    }
}
