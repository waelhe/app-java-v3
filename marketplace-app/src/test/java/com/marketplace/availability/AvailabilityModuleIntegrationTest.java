package com.marketplace.availability;

import com.marketplace.shared.web.ApiVersioningConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AvailabilityModuleIntegrationTest {

    @Autowired
    private AvailabilityService availabilityService;

    @TestConfiguration
    @EnableJpaAuditing
    static class AuditingConfig {
        @Bean
        AuditorAware<String> auditorAware() {
            return Optional::empty;
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        ApiVersioningConfig apiVersioningConfig() {
            return new ApiVersioningConfig();
        }
    }

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
