package com.marketplace.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import com.marketplace.shared.api.PropertyDetailsPort.PropertyView;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * L39 (realestate systems plan §5 — SEO and structured data): the
 * schema.org {@code RealEstateListing} JSON-LD block the public listing
 * detail response gains — the data the frontend embeds verbatim in its
 * page as {@code <script type="application/ld+json">} so search engines
 * verify the listing's structured facts ("الحقول تخدم الواجهة لتحقّق
 * محركات البحث — الباك اند يخدم البيانات فقط").
 *
 * <p><b>Every field is a measured schema.org fact (the comparative
 * field test of the plan's criterion 3):</b>
 * <ul>
 *   <li>{@code RealEstateListing} (Thing &gt; CreativeWork &gt; WebPage &gt;
 *       RealEstateListing — "A RealEstateListing is a listing that
 *       describes one or more real-estate Offers (whose
 *       businessFunction is typically to lease out, or to sell)").</li>
 *   <li>{@code name}/{@code description}/{@code url} — Thing properties.</li>
 *   <li>{@code dateModified} — CreativeWork property, mapped from the
 *       entity's {@code updated_at} audit column (exactly the fact that
 *       column claims). {@code datePosted} — the type's own "Publication
 *       date of an online listing" — is deliberately OMITTED: the entity
 *       records no publication instant ({@code activate()} changes the
 *       status with no timestamp — a listing drafted days before
 *       activation would carry a fabricated date under {@code createdAt};
 *       CodeRabbit round 1 verified the schema carries no such column).
 *       The field returns when a publication timestamp becomes a
 *       first-class fact — an honest omission, never a guess.</li>
 *   <li>{@code offers} with an {@code Offer} carrying {@code price},
 *       {@code priceCurrency} (ISO 4217) and {@code businessFunction} —
 *       the type's own documentation directs the transaction kind there;
 *       the enumeration values are the GoodRelations URIs schema.org
 *       adopted: {@code http://purl.org/goodrelations/v1#LeaseOut} for
 *       RENT and {@code http://purl.org/goodrelations/v1#Sell} for SALE
 *       (measured from schema.org/BusinessFunction).</li>
 *   <li>{@code address} as a {@code PostalAddress} — the L30
 *       administrative chain mapped by level to the standard's three
 *       place fields: level 0 (country) → {@code addressCountry},
 *       level 1 (governorate) → {@code addressRegion}, level 2 (city)
 *       → {@code addressLocality}. The neighborhood level carries no
 *       PostalAddress field in the standard (its properties are
 *       streetAddress/postOfficeBoxNumber/addressLocality/
 *       addressRegion/postalCode/addressCountry) — it is NOT invented
 *       into the structured block; it stays in the response's property
 *       block (locationId) and the geo endpoint for the frontend to
 *       display.</li>
 * </ul>
 *
 * <p><b>Honesty rules:</b> the block exists only for listings that HAVE
 * a real-estate property block (the L31 embed — a service listing is
 * not a RealEstateListing); {@code url} is present only when the public
 * site origin is bound (a fabricated URL helps no one); absent
 * optional fields are omitted ({@code NON_NULL} — the JSON-LD
 * convention is omission, not null).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealEstateListingJsonLd(
        @JsonProperty("@context") String context,
        @JsonProperty("@type") String type,
        String name,
        String description,
        String url,
        Instant dateModified,
        Offers offers,
        Address address
) {

    public static final String SCHEMA_CONTEXT = "https://schema.org";
    public static final String SCHEMA_TYPE = "RealEstateListing";

    /** RENT → lease out (the GoodRelations enumeration schema.org adopted). */
    static final String BUSINESS_FUNCTION_LEASE_OUT = "http://purl.org/goodrelations/v1#LeaseOut";
    /** SALE → sell (the same enumeration's default member). */
    static final String BUSINESS_FUNCTION_SELL = "http://purl.org/goodrelations/v1#Sell";

    /**
     * The block for one public listing detail: the listing's core facts
     * plus its real-estate property block plus the resolved location
     * chain, with the public page URL only when the origin is bound.
     *
     * @param listing  the listing detail response (core + property block)
     * @param chain    the L30 location chain (attached node → root; may
     *                 be empty when the property block has no location
     *                 or the node is absent from the tree)
     * @param pageUrl  the public listing page URL — empty when the SEO
     *                 capability is off (the url field is omitted)
     */
    public static RealEstateListingJsonLd of(ListingResponse listing,
                                             List<GeoNode> chain,
                                             Optional<String> pageUrl) {
        PropertyView property = listing.property();
        return new RealEstateListingJsonLd(
                SCHEMA_CONTEXT,
                SCHEMA_TYPE,
                listing.title(),
                listing.description(),
                pageUrl.orElse(null),
                listing.updatedAt(),
                new Offers(listing.price(), listing.currency(),
                        businessFunctionOf(property.purpose())),
                addressOf(chain));
    }

    static String businessFunctionOf(com.marketplace.shared.api.PropertyPurpose purpose) {
        return purpose == com.marketplace.shared.api.PropertyPurpose.SALE
                ? BUSINESS_FUNCTION_SELL
                : BUSINESS_FUNCTION_LEASE_OUT;
    }

    private static Address addressOf(List<GeoNode> chain) {
        if (chain.isEmpty()) {
            return null;
        }
        String country = null;
        String region = null;
        String locality = null;
        for (GeoNode node : chain) {
            switch (node.level()) {
                case 0 -> country = node.nameAr();
                case 1 -> region = node.nameAr();
                case 2 -> locality = node.nameAr();
                default -> { /* the neighborhood: no PostalAddress field — see the class javadoc */ }
            }
        }
        if (country == null && region == null && locality == null) {
            return null;
        }
        return new Address(country, region, locality);
    }

    /**
     * The listing's Offer — price in major units with its ISO 4217
     * currency (the response's own price fields) and the transaction
     * kind as the businessFunction enumeration value.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Offers(
            @JsonProperty("@type") String type,
            java.math.BigDecimal price,
            String priceCurrency,
            String businessFunction
    ) {
        static final String OFFER_TYPE = "Offer";

        Offers(java.math.BigDecimal price, String priceCurrency, String businessFunction) {
            this(OFFER_TYPE, price, priceCurrency, businessFunction);
        }
    }

    /**
     * The PostalAddress from the L30 administrative chain (level-mapped
     * — see the class javadoc for why the neighborhood is not a field).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Address(
            @JsonProperty("@type") String type,
            String addressCountry,
            String addressRegion,
            String addressLocality
    ) {
        static final String ADDRESS_TYPE = "PostalAddress";

        Address(String addressCountry, String addressRegion, String addressLocality) {
            this(ADDRESS_TYPE, addressCountry, addressRegion, addressLocality);
        }
    }
}
