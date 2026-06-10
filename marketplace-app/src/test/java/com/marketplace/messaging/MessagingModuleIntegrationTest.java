package com.marketplace.messaging;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.config.MarketplaceProperties;
import com.marketplace.shared.security.CurrentUserProvider;
import com.marketplace.shared.web.ApiVersioningConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
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

    @TestConfiguration
    static class TestBeans {
        @Bean
        ApiVersioningConfig apiVersioningConfig() {
            return new ApiVersioningConfig();
        }

        @Bean
        MarketplaceProperties marketplaceProperties() {
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

    @Test
    void contextLoads() {
    }

    @Test
    void createConversation_saves() {
        var conversation = messagingService.createConversation(CONSUMER_ID, UUID.randomUUID());
        assertThat(conversation.getId()).isNotNull();
    }
}
