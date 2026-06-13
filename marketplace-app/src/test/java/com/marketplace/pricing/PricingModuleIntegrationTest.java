package com.marketplace.pricing;

import test.config.ModuleTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class PricingModuleIntegrationTest {

    @Autowired
    private PricingService pricingService;

    @Test
    void contextLoads() {
    }

    @Test
    void createAndActivateRule() {
        var rule = pricingService.createRule("Test Rule", "services",
                new BigDecimal("0.1500"), new BigDecimal("0.0500"));

        assertThat(rule.getId()).isNotNull();
        assertThat(rule.getName()).isEqualTo("Test Rule");
        assertThat(rule.isActive()).isTrue();

        var activated = pricingService.activate(rule.getId());
        assertThat(activated.isActive()).isTrue();

        var deactivated = pricingService.deactivate(rule.getId());
        assertThat(deactivated.isActive()).isFalse();

        var rules = pricingService.listRules();
        assertThat(rules).hasSize(1);

        pricingService.deleteById(rule.getId());
        assertThat(pricingService.listRules()).isEmpty();
    }

    @Test
    void findById_returnsEmptyForMissing() {
        var result = pricingService.findById(UUID.randomUUID());
        assertThat(result).isEmpty();
    }
}
