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
 * <p><b>Bean resolution for {@link MarketplaceProperties} in {@code @ApplicationModuleTest} slice</b>:
 *
 * <p>The messaging module slice includes {@code WebSocketConfig}, whose constructor
 * requires a {@link MarketplaceProperties} bean. In a slice test context, the
 * {@code @EnableConfigurationProperties} on {@code MarketplaceApplication} may not
 * fully activate (the bean is registered under the conventional name
 * {@code <prefix>-<fqn>} per Spring Boot Reference). While {@link ModuleTestConfig}
 * provides a {@code @Primary @Bean}, slice context bean resolution ordering can
 * cause the {@code WebSocketConfig} constructor to fail with
 * "No qualifying bean of type 'MarketplaceProperties'".
 *
 * <p><b>Fix</b>: declare a local {@code @TestConfiguration} with an explicit
 * {@code @Primary @Bean MarketplaceProperties}. This guarantees the bean is
 * available to {@code WebSocketConfig} regardless of slice context ordering.
 * Unlike the prior (removed) {@code MessagingTestConfig}, this version provides
 * a <em>real</em> bean (not a mock) with concrete values, so
 * {@code WebSocketConfig.registerStompEndpoints()} can call
 * {@code properties.cors().allowedOrigins()} without NPE.
 *
 * <p><b>No {@code allow-bean-definition-overriding}</b> is needed because this
 * bean has a distinct name ({@code messagingMarketplaceProperties}) from the
 * {@code ModuleTestConfig} one ({@code marketplaceProperties}) — both coexist,
 * and {@code @Primary} ensures this one wins for injection points.
 *
 * <p>Reference:
 * <a href="https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.detecting-configuration">
 * Spring Boot Reference — Detecting Test Configuration</a>
 * <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.enabling">
 * Spring Boot Reference — Enabling @ConfigurationProperties-annotated Types</a>
 * <a href="https://docs.spring.io/spring-modulith/reference/testing.html">
 * Spring Modulith Reference — Integration Testing Application Modules</a>
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
     * <p>This bean is {@code @Primary} so it wins over any
     * {@code @EnableConfigurationProperties}-registered bean in the slice context.
     * The values match {@code application-test.yml}'s {@code marketplace.*} section
     * to keep behavior consistent with the rest of the test suite.
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
                            new MarketplaceProperties.Security.AuthServer("http://localhost:8080")
                    )
            );
        }
    }
}
