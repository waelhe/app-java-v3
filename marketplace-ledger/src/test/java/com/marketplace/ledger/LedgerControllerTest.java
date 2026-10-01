package com.marketplace.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
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
        var balance = ProviderBalanceResponse.from(ProviderBalance.empty(providerId, "SAR"));
        when(ledgerService.creditFromPayment(providerId, paymentIntentId, amountCents, "SAR")).thenReturn(balance);

        ResponseEntity<ProviderBalanceResponse> result =
                controller.creditProvider(providerId, paymentIntentId, amountCents, "SAR");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(balance);
    }

    @Test
    void creditProviderWithoutCurrencyKeepsTheHouseDefault() {
        // R9: a blank currency rides the runtime path's own fallback
        // (Currencies.normalizeOrDefault → SAR) inside the service.
        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        long amountCents = 5000;
        var balance = ProviderBalanceResponse.from(ProviderBalance.empty(providerId, "SAR"));
        when(ledgerService.creditFromPayment(providerId, paymentIntentId, amountCents, null)).thenReturn(balance);

        ResponseEntity<ProviderBalanceResponse> result =
                controller.creditProvider(providerId, paymentIntentId, amountCents, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(balance);
    }

    @Test
    void getProviderBalancesReturnsOk() {
        // R9: the read surface is the multi-currency list — one row per
        // currency the provider holds.
        UUID providerId = UUID.randomUUID();
        var balances = List.of(
                ProviderBalanceResponse.from(ProviderBalance.empty(providerId, "SAR")),
                ProviderBalanceResponse.from(ProviderBalance.empty(providerId, "USD")));
        when(ledgerService.getBalances(providerId)).thenReturn(balances);

        ResponseEntity<List<ProviderBalanceResponse>> result = controller.getProviderBalances(providerId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(balances);
        assertThat(result.getBody()).hasSize(2);
    }
}
