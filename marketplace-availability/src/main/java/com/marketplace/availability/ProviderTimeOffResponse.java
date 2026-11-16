package com.marketplace.availability;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The REST contract for a provider's time-off window. The entity exposes
 * no getters for {@code providerId}/{@code startsAt}/{@code endsAt} (JPA
 * field access; Jackson's default getter detection), so the historical
 * wire shape was {@code id} plus the shared {@code BaseEntity} audit
 * columns. Introduced so the HTTP boundary stops exposing the JPA entity
 * (audit 2026-09-25, clean-architecture finding 1); {@code @Schema(name)}
 * keeps the OpenAPI schema name stable.
 *
 * <p><b>CodeRabbit agent findings row 33 (CWE-200, 2026-10-25):</b> of
 * that measured surface, the audit identity columns
 * ({@code createdBy}/{@code updatedBy}) carry the account subject — the
 * login email for native registrations — and leave the shared contract
 * (the same law {@code AvailabilitySlotResponse} now documents); what
 * remains is the row's identity, concurrency version and audit
 * timestamps. Identities stay internal to the module's {@code _aud}
 * mirrors; any future audit surface is a separate endpoint with explicit
 * authorization.</p>
 */
@Schema(name = "ProviderTimeOff")
public record ProviderTimeOffResponse(
        UUID id,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    static ProviderTimeOffResponse from(ProviderTimeOff timeOff) {
        return new ProviderTimeOffResponse(
                timeOff.getId(),
                timeOff.getVersion(),
                timeOff.getCreatedAt(),
                timeOff.getUpdatedAt());
    }
}
