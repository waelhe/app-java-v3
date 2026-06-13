package com.marketplace.ledger;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class LedgerModuleIntegrationTest {

    @MockitoBean
    PaymentIntentLookupPort paymentIntentLookupPort;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private LedgerService ledgerService;

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
