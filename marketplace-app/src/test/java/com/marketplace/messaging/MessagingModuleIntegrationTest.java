package com.marketplace.messaging;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.config.MarketplaceProperties;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration test for the Messaging module.
 *
 * <p><b>MarketplaceProperties bean in slice context:</b> The inner
 * {@code MessagingTestConfig} provides a {@code @Primary @Bean} with a
 * <em>real</em> {@link MarketplaceProperties} instance. This is needed because
 * {@code @ApplicationModuleTest} (STANDALONE mode) limits component scanning to
 * the module's base package ({@code com.marketplace.messaging}), and
 * {@code WebSocketConfig}'s constructor injection may fail before
 * {@link ModuleTestConfig}'s {@code @Bean} is resolved in the slice context.
 *
 * <p><b>No bean name conflict:</b> The prior version of this inner config used
 * method name {@code marketplaceProperties} (same as {@code ModuleTestConfig}'s
 * @Bean method), causing a bean name collision that required
 * {@code spring.main.allow-bean-definition-overriding: true}. This version uses
 * a distinct method name ({@code messagingMarketplaceProperties}) — both beans
 * coexist, and {@code @Primary} ensures this one wins for injection.
 *
 * <p><b>Real bean, not mock:</b> The prior version returned a mock that only
 * stubbed {@code cors()} but returned null for {@code security()}, risking NPEs.
 * This version returns a real bean with all properties populated.
 *
 * <p>Reference:
 * <a href="https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.detecting-configuration">
 * Spring Boot Reference — Detecting Test Configuration</a>
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

    /**
     * Provides a real (non-mock) {@link MarketplaceProperties} bean for
     * {@code WebSocketConfig} in the messaging module slice.
     *
     * <p>Method name is {@code messagingMarketplaceProperties} (distinct from
     * {@code ModuleTestConfig}'s {@code marketplaceProperties}) to avoid bean
     * name conflict.
     */
    @TestConfiguration
    static class MessagingTestConfig {

        @Bean
        @Primary
        MarketplaceProperties messagingMarketplaceProperties() {
            return new MarketplaceProperties(
                    new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                    new MarketplaceProperties.Security(
                            new MarketplaceProperties.Security.Jwt(
                                    new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", ""),
                                    "marketplace-api"
                            ),
                                    new MarketplaceProperties.Security.Session(2),
                                    new MarketplaceProperties.Security.OAuth2(
                                            new MarketplaceProperties.Security.OAuth2.Client("", ""))
                            )
            );
        }
    }
}
