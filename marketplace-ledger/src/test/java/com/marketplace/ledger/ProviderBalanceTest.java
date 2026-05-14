package com.marketplace.ledger;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderBalanceTest {

    @Test
    void emptyCreatesZeroBalance() {
        var id = UUID.randomUUID();
        var balance = ProviderBalance.empty(id);
        assertEquals(id, balance.getId());
        assertEquals(0, balance.getAvailableCents());
    }

    @Test
    void creditAddsToAvailable() {
        var balance = ProviderBalance.empty(UUID.randomUUID());
        balance.credit(5000);
        assertEquals(5000, balance.getAvailableCents());
        balance.credit(3000);
        assertEquals(8000, balance.getAvailableCents());
    }
}
