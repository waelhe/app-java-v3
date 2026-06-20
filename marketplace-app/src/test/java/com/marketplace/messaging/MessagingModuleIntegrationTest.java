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
 * <p><b>MarketplaceProperties bean:</b> No inner {@code @TestConfiguration} is needed
 * here. {@link ModuleTestConfig} (imported via {@code @Import}) provides a
 * {@code @Primary @Bean MarketplaceProperties} with real values. The prior inner
 * {@code MessagingTestConfig} declared a {@code @Bean} with the same method name
 * ({@code marketplaceProperties}) but returned a <em>mock</em>, causing a bean name
 * conflict with {@code ModuleTestConfig}'s bean. This conflict was masked by
 * {@code spring.main.allow-bean-definition-overriding: true}.
 *
 * <p>Removing the inner config eliminates the name conflict. The real bean from
 * {@code ModuleTestConfig} is superior to the mock because it provides concrete
 * values for all properties (not just {@code cors()}), preventing NPEs in
 * {@code WebSocketConfig.registerStompEndpoints()} and other code that accesses
 * {@code properties.security()} etc.
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
}
