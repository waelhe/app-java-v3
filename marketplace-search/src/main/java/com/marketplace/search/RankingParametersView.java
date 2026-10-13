package com.marketplace.search;

import java.util.List;

/**
 * ADR-0011 (D-15 — DSA (EU) 2022/2065 Art. 27(1)/(2) + Art. 26(1)(d), the
 * EUR-Lex text): the machine truth of the ranking the platform presents.
 * Art. 27(1): "Providers of online platforms that use recommender systems
 * shall set out in their terms and conditions, in plain and intelligible
 * language, the main parameters used in their recommender systems, as well
 * as any options for the recipients of the service to modify or influence
 * those main parameters." Art. 27(2): the parameters "shall explain why
 * certain information is suggested" — "(a) the criteria which are most
 * significant in determining the information suggested to the recipient of
 * the service; (b) the reasons for the relative importance of those
 * parameters." Art. 26(1)(d): for each advertisement, "meaningful
 * information directly and easily accessible ... about the main parameters
 * used to determine the recipient to whom the advertisement is presented
 * and, where applicable, about how to change those parameters."
 *
 * <p>This view is REFERENCE DATA in the categories-registry pattern — a
 * closed shape (no free HTML, no executable payloads; the ADR-0005
 * validator's own law), composed from the modules' documented laws and
 * kept true by the tests that pin those laws:
 * <ul>
 *   <li>the promoted tier — the {@code ProviderListingRepository} ORDER
 *       BY's first flag and {@code ProviderListingSpecifications#boostFirst}
 *       (the live paid campaign with remaining budget, or the admin
 *       featured window — expiry-aware at query time), identified on every
 *       row by the {@code ListingSummary#promoted()} field;</li>
 *   <li>the organic criteria — the Arabic FTS relevance
 *       ({@code ts_rank} over the 'arabic' configuration with the pg_trgm
 *       typo fallback), the whitelisted sorts ({@code SearchSorts}: price,
 *       newest, area, distance, rating), the V99 composite rating score
 *       (rating × log(count) × completeness × recency), and the id ASC
 *       tiebreak (the L32 total order — no wobbling pages);</li>
 *   <li>the recipient's options — the sort whitelist, the radius triple,
 *       the filter axes, and the labeled promoted tier itself.</li>
 * </ul>
 *
 * <p>The declaration of commercial communications (Art. 26(2)) rides the
 * community surface: a post's {@code declaredCommercial} self-declaration
 * is carried on every read of the post (the real-time marking of
 * recipient-declared commercial content).
 */
public record RankingParametersView(
        String version,
        List<ParameterGroup> mainParameters,
        List<RecipientOption> recipientOptions
) {

    /** One ranking surface's main parameters, with the why (Art. 27(2)(b)). */
    public record ParameterGroup(
            String surface,
            List<String> parameters,
            String relativeImportanceReason
    ) {
    }

    /** One option the recipient holds to modify or influence the order (Art. 27(3)). */
    public record RecipientOption(
            String option,
            String how
    ) {
    }

    /** The composition — the documented laws, one sentence each. */
    static RankingParametersView thePlatformTruth() {
        return new RankingParametersView(
                "adr-0011/dsa-27",
                List.of(
                        new ParameterGroup(
                                "The promoted tier (every ordered listing surface)",
                                List.of(
                                        "A paid promotion: the provider's own campaign priced per impression "
                                                + "and per click against the campaign's budget, live only while ACTIVE "
                                                + "with remaining budget and inside its duration (an expired window or "
                                                + "an exhausted budget drops out of the tier at query time).",
                                        "An admin featured window: the platform's own editorial feature, bounded "
                                                + "by its promoted_until instant and expired windows drop out at query time.",
                                        "Promoted listings ride FIRST within the requested sort — organic matches "
                                                + "rank within each group; relevance is never multiplied by an auction.",
                                        "Every promoted row is labeled by the promoted field on the response row "
                                                + "itself, in the same response that presents the reordering."
                                ),
                                "Paid visibility is the platform's commercial model and is explicitly labeled "
                                        + "as such; it is bounded (one live campaign per listing, budget-capped) and "
                                        + "never overrides the recipient's own filter axes."
                        ),
                        new ParameterGroup(
                                "The organic ordering (text queries)",
                                List.of(
                                        "Arabic full-text relevance: ts_rank over the 'arabic' configuration of "
                                                + "title and description, with a word-similarity fallback so one-edit "
                                                + "typos still surface the intended matches.",
                                        "The id tiebreak: equal-relevance rows keep a stable, deterministic order "
                                                + "across pages."
                                ),
                                "Relevance to the recipient's own query words is the most significant "
                                        + "criterion — the query is the recipient's stated intent."
                        ),
                        new ParameterGroup(
                                "The organic ordering (filtered browse)",
                                List.of(
                                        "The requested sort: price (ascending or descending), newest, area "
                                                + "(declared square meters), distance (nearest first, requires the "
                                                + "radius), rating (highest first — the daily-computed composite of "
                                                + "the verified rating, the review volume, the listing completeness "
                                                + "and recency).",
                                        "The stay-window and capacity filters restrict the set before any ordering.",
                                        "The id tiebreak closes every ordering."
                                ),
                                "Absent a text query, the recipient's own chosen sort is the most "
                                        + "significant criterion — the surface serves the recipient's stated "
                                        + "preference, not a profile."
                        ),
                        new ParameterGroup(
                                "What does NOT exist",
                                List.of(
                                        "No recipient-profile targeting: no advertisement is selected per "
                                                + "recipient by profiling, and no special-category data feeds any "
                                                + "advertising decision.",
                                        "No private ranking: the same query and sort answer every recipient the "
                                                + "same page — the only per-recipient inputs are the recipient's own "
                                                + "filters and chosen sort."
                                ),
                                "The recipient can verify this: the ordering's inputs are the query, the "
                                        + "filters and the sort they chose."
                        )
                ),
                List.of(
                        new RecipientOption(
                                "Choose the ordering",
                                "The sort parameter on the search and browse surfaces (the whitelist: "
                                        + "price, newest, area, distance, rating) — an unsupported sort is "
                                        + "rejected with 400, never silently ignored."
                        ),
                        new RecipientOption(
                                "Set the proximity",
                                "The radius triple (latitude, longitude, radius in km, up to 50 km) with "
                                        + "sort=distance orders nearest-first."
                        ),
                        new RecipientOption(
                                "Filter the set",
                                "The filter axes (category, price bounds, stay window, guests, the "
                                        + "real-estate facets, the rating floor) restrict what any ordering can "
                                        + "present."
                        ),
                        new RecipientOption(
                                "Discount the promoted tier",
                                "Every promoted row carries the promoted label on the row itself — the "
                                        + "recipient reads the organic set by looking past the labeled tier."
                        ),
                        new RecipientOption(
                                "Declare commercial content",
                                "A community post can carry the author's own commercial-communications "
                                        + "declaration; the declaration rides the post on every read."
                        )
                )
        );
    }
}
