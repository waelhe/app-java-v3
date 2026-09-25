package com.marketplace.availability;

import test.config.ModuleTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class AvailabilityModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private AvailabilityService availabilityService;

    @Test
    void contextLoads() {
    }

    @Test
    void createSlot_persistsAndRetrieves() {
        var providerId = UUID.randomUUID();
        var startsAt = Instant.now();
        var endsAt = startsAt.plusSeconds(3600);

        var slot = availabilityService.createSlot(providerId, startsAt, endsAt);
        assertThat(slot.getId()).isNotNull();
        assertThat(slot.getProviderId()).isEqualTo(providerId);

        var slots = availabilityService.getSlots(providerId, startsAt.minusSeconds(60), endsAt.plusSeconds(60));
        assertThat(slots).isNotEmpty();
        assertThat(slots.stream().anyMatch(s -> s.getId().equals(slot.getId()))).isTrue();
    }
}
