package com.marketplace.institutions;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * B-13 (compliance plan C.3): the registry's read model — the
 * {@code from}-factory house shape. The list rows carry the row's own
 * fields (the {@code jsonLd} block omitted — {@code NON_NULL}); the
 * DETAIL read carries the schema.org block assembled over the resolved
 * administrative chain (the provider page's own pattern: the
 * structured-data surface rides the public page, not the list).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InstitutionResponse(
        @Schema(description = "The registry entry's id.") UUID id,
        @Schema(description = "The institution's registry name.") String name,
        @Schema(description = "SCHOOL, UNIVERSITY, CLINIC, MOSQUE, CHARITY, GOVERNMENT, COMPANY or NGO.") String type,
        @Schema(description = "The registering representative's user id (users.id space).") UUID representativeId,
        @Schema(description = "The geo anchor — a level-3 neighborhood node id.") UUID locationId,
        @Schema(description = "The optional free-text street address.") String address,
        @Schema(description = "The optional public contact phone.") String phone,
        @Schema(description = "The optional public website.") String website,
        @Schema(description = "The optional description.") String description,
        @Schema(description = "UNVERIFIED, PENDING, VERIFIED or REJECTED — the trust signal visible on every read (the honest registry).") String verificationState,
        @Schema(description = "The registration time.") Instant registeredAt,
        @Schema(description = "The row's creation time.") Instant createdAt,
        @Schema(description = "The row's last-update time.") Instant updatedAt,
        @Schema(description = "The schema.org Organization JSON-LD block — the detail read only, over the resolved administrative chain.") InstitutionJsonLd jsonLd
) {

    /** The list row — no JSON-LD (the structured-data surface rides the detail page alone). */
    public static InstitutionResponse from(Institution institution) {
        return from(institution, null);
    }

    /** The detail read — the JSON-LD block assembled over the resolved chain. */
    public static InstitutionResponse from(Institution institution, List<com.marketplace.shared.api.GeoLookupPort.GeoNode> chain) {
        return new InstitutionResponse(
                institution.getId(),
                institution.getName(),
                institution.getType().name(),
                institution.getRepresentativeId(),
                institution.getLocationId(),
                institution.getAddress(),
                institution.getPhone(),
                institution.getWebsite(),
                institution.getDescription(),
                institution.getVerificationState().name(),
                institution.getRegisteredAt(),
                institution.getCreatedAt(),
                institution.getUpdatedAt(),
                chain == null ? null : InstitutionJsonLd.of(institution, chain));
    }
}
