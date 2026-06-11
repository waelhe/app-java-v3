package com.marketplace.ledger;

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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class LedgerModuleIntegrationTest {

    @Autowired
    private LedgerService ledgerService;

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
    void creditFromPayment_createsBalance() {
        var balance = ledgerService.creditFromPayment(UUID.randomUUID(), UUID.randomUUID(), 1000L);
        assertThat(balance).isNotNull();
        assertThat(balance.getAvailableCents()).isEqualTo(1000L);
    }
}
