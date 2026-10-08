package com.marketplace.institutions;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.marketplace.shared.api.GeoLookupPort;

import java.util.List;

/**
 * B-13 (compliance plan C.3): the schema.org {@code Organization}
 * JSON-LD block the public institution page carries — the
 * {@code ProviderBusinessJsonLd}/{@code RealEstateListingJsonLd} house
 * pattern: every field is a measured schema.org fact, absent values are
 * omitted (never fabricated, never zeroed).
 *
 * <p><b>The measured schema.org mapping:</b></p>
 * <ul>
 *   <li>{@code Organization} (Thing &gt; Organization) — the type of the
 *       page's subject: an institution. The platform's own
 *       {@link InstitutionType} vocabulary (SCHOOL/CLINIC/MOSQUE/…) is
 *       the REGISTRY's axis, not schema.org's — mapping it to the
 *       specific subtypes (School/Hospital/Mosque) would fabricate a
 *       precision the platform's vocabulary does not carry (a CLINIC
 *       entry may be a physician's office), so the honest generic
 *       carries it.</li>
 *   <li>{@code name}/{@code description} — Thing properties, from the
 *       registry row (description omitted when absent).</li>
 *   <li>{@code url} — the declared website, present only when the
 *       institution declared one (the L39 honesty rule).</li>
 *   <li>{@code telephone} — the declared public contact, same rule.</li>
 *   <li>{@code address} as a {@code PostalAddress} — the L30
 *       administrative-chain level mapping (the
 *       {@code RealEstateListingJsonLd} twin's own discipline): level 0
 *       (country) → {@code addressCountry}, level 1 (governorate) →
 *       {@code addressRegion}, level 2 (city) → {@code addressLocality}.
 *       The neighborhood level carries no PostalAddress field in the
 *       standard — it is NOT invented here; the institution's own
 *       free-text {@code address} rides {@code streetAddress} only when
 *       declared.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstitutionJsonLd(
        @JsonProperty("@context") String context,
        @JsonProperty("@type") String type,
        String name,
        String description,
        String url,
        String telephone,
        PostalAddress address
) {

    public static final String SCHEMA_CONTEXT = "https://schema.org";
    public static final String SCHEMA_TYPE = "Organization";

    /**
     * The block from the registry row + the RESOLVED administrative
     * chain (the service walked the geo node's {@code parentId} to the
     * root through {@code GeoLookupPort} — one node per level, the
     * measured tree facts). Every optional field rides its own presence
     * rule — absent is omitted.
     */
    public static InstitutionJsonLd of(Institution institution, List<GeoLookupPort.GeoNode> chain) {
        return new InstitutionJsonLd(
                SCHEMA_CONTEXT,
                SCHEMA_TYPE,
                institution.getName(),
                institution.getDescription(),
                institution.getWebsite(),
                institution.getPhone(),
                PostalAddress.of(institution, chain));
    }

    /**
     * The L30 level-mapped PostalAddress — the listing twin's own
     * discipline: only the levels the standard names are carried, the
     * neighborhood level is not invented, and the free-text street
     * address rides {@code streetAddress} only when declared.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PostalAddress(
            @JsonProperty("streetAddress") String streetAddress,
            @JsonProperty("addressLocality") String addressLocality,
            @JsonProperty("addressRegion") String addressRegion,
            @JsonProperty("addressCountry") String addressCountry
    ) {

        static PostalAddress of(Institution institution, List<GeoLookupPort.GeoNode> chain) {
            String locality = null;
            String region = null;
            String country = null;
            for (GeoLookupPort.GeoNode node : chain) {
                switch (node.level()) {
                    // The display language is the node's own Arabic name — one
                    // convention for the whole structured-data surface.
                    case 0 -> country = node.nameAr();
                    case 1 -> region = node.nameAr();
                    case 2 -> locality = node.nameAr();
                    default -> { /* the neighborhood (3): no PostalAddress field — see the class javadoc */ }
                }
            }
            return new PostalAddress(institution.getAddress(), locality, region, country);
        }
    }
}
