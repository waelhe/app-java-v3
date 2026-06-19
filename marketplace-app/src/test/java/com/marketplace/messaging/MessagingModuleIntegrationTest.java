package com.marketplace.messaging;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration test for the Messaging module.
 *
 * <p>The prior inner {@code MessagingTestConfig} declared a duplicate
 * {@code @Bean MarketplaceProperties marketplaceProperties()} that collided with the
 * one in {@link ModuleTestConfig} (both registered under the same bean name).
 * The collision was masked by {@code spring.main.allow-bean-definition-overriding: true}
 * in {@code application-test.yml}, which silently let the second definition win.
 *
 * <p>Per Spring Boot Reference ("Enabling @ConfigurationProperties-annotated Types"),
 * when {@code @EnableConfigurationProperties(MarketplaceProperties.class)} is present
 * on {@code MarketplaceApplication}, the bean is registered by Spring Boot itself
 * under the conventional name {@code <prefix>-<fqn>}. The {@code @Bean} in
 * {@link ModuleTestConfig} (marked {@code @Primary}) is a test-only override that
 * provides concrete values without relying on YAML binding. The duplicate in
 * {@code MessagingTestConfig} was therefore redundant and has been removed.
 *
 * <p>Reference:
 * <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.enabling">
 * Spring Boot Reference -- Enabling @ConfigurationProperties-annotated Types</a>
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class MessagingModuleIntegrationTest {

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private MessagingService messagingService;

    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final UUID CONSUMER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var bookingInfo = new BookingInfo(PROVIDER_ID, CONSUMER_ID, "CONFIRMED",
                1000L, "USD", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(any())).thenReturn(bookingInfo);
    }

    @Test
    void contextLoads() {
    }

    @Test
    void createConversation_saves() {
        var conversation = messagingService.createConversation(CONSUMER_ID, UUID.randomUUID());
        assertThat(conversation.getId()).isNotNull();
    }
}
