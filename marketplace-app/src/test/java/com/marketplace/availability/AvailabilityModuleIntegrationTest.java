package com.marketplace.availability;

import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class AvailabilityModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private AvailabilityService availabilityService;

    // Row 11's carried eligibility gate resolves the provider through the
    // cross-module ProviderLookupPort seam — the real adapter lives in
    // marketplace-provider and joins in the full-context integration
    // tests; at this slice boundary the house @MockitoBean convention
    // applies (the CatalogModuleIntegrationTest pattern).
    @MockitoBean
    ProviderLookupPort providerLookupPort;

    @Test
    void contextLoads() {
    }

    @Test
    void createSlot_persistsAndRetrieves() {
        var providerId = UUID.randomUUID();
        // The carried row-11 contract: a VERIFIED profile alone publishes
        // bookable slots — the slice's stand-in answers the eligibility
        // fact for the seeded provider (the real adapter's statuses are the
        // full-context tests' own ground).
        when(providerLookupPort.findByUserId(providerId)).thenReturn(Optional.of(
                new ProviderSummary(UUID.randomUUID(), "Eligible Provider", "VERIFIED", providerId)));
        var startsAt = Instant.now();
        var endsAt = startsAt.plusSeconds(3600);

        var slot = availabilityService.createSlot(providerId, startsAt, endsAt);
        assertThat(slot.id()).isNotNull();
        assertThat(slot.providerId()).isEqualTo(providerId);

        var slots = availabilityService.getSlots(providerId, startsAt.minusSeconds(60), endsAt.plusSeconds(60));
        assertThat(slots).isNotEmpty();
        assertThat(slots.stream().anyMatch(s -> s.id().equals(slot.id()))).isTrue();
    }
}
