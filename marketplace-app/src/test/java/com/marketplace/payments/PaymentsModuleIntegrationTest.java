package com.marketplace.payments;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import com.marketplace.shared.web.ApiVersioningConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PaymentsModuleIntegrationTest {

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private PaymentsService paymentsService;

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
    void listIntents_returnsEmptyPage() {
        var page = paymentsService.listIntents(Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }

    @Test
    void processWebhookEvent_returnsTrue() {
        var result = paymentsService.processWebhookEvent("stripe", "evt_test", "payment.intent.succeeded", "test-sig");
        assertThat(result).isTrue();
    }
}
