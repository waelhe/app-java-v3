package com.marketplace.availability;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CodeRabbit agent findings row 33 (CWE-200, 2026-10-25): the module's
 * REST contracts must carry NO audit identities. The audit columns
 * ({@code createdBy}/{@code updatedBy}) hold the account subject —
 * {@code AuditorAwareImpl} returns {@code Authentication.getName()},
 * which for native registrations IS the login email — and the module's
 * read path ({@code GET /providers/{id}/availability}) serves ANY
 * authenticated reader, so a shared response was a private-contact
 * disclosure waiting for every future read endpoint. This guard pins
 * the law for ALL three availability responses at once: no component of
 * any response record may ever be named an audit identity. Audit
 * history stays internal (the {@code _aud} mirrors); any future surface
 * that legitimately needs identities is a separate endpoint with
 * explicit authorization, not a field on the shared contract.
 */
class AvailabilityResponsesAuditContractTest {

    @Test
    void noAvailabilityResponseExposesAuditIdentities() {
        List<Class<? extends Record>> contracts = List.of(
                AvailabilitySlotResponse.class,
                ProviderAvailabilityRuleResponse.class,
                ProviderTimeOffResponse.class);

        for (Class<? extends Record> contract : contracts) {
            List<String> componentNames = Arrays.stream(contract.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            assertThat(componentNames)
                    .as("the %s wire contract must not expose audit identities (row 33)",
                            contract.getSimpleName())
                    .doesNotContain("createdBy", "updatedBy");
            // The operational surface stays: every contract still carries the
            // audit TIMESTAMPS (not personal data) and the concurrency version.
            assertThat(componentNames)
                    .as("the %s contract keeps its operational surface while dropping identities",
                            contract.getSimpleName())
                    .containsAnyOf("createdAt", "version");
        }
    }
}
