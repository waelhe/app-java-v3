package com.marketplace.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerControllerTest {

    @Mock
    private LedgerService ledgerService;

    @InjectMocks
    private LedgerController controller;

    @Test
    void creditProviderReturnsOk() {
        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        long amountCents = 5000;
        var balance = ProviderBalance.empty(providerId);
        when(ledgerService.creditFromPayment(providerId, paymentIntentId, amountCents)).thenReturn(balance);

        ResponseEntity<ProviderBalance> result = controller.creditProvider(providerId, paymentIntentId, amountCents);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(balance);
    }

    @Test
    void getProviderBalanceReturnsOk() {
        UUID providerId = UUID.randomUUID();
        var balance = ProviderBalance.empty(providerId);
        when(ledgerService.getBalance(providerId)).thenReturn(balance);

        ResponseEntity<ProviderBalance> result = controller.getProviderBalance(providerId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(balance);
    }
}
