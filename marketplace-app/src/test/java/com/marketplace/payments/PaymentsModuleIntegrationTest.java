package com.marketplace.payments;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class PaymentsModuleIntegrationTest {

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private PaymentsService paymentsService;

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
