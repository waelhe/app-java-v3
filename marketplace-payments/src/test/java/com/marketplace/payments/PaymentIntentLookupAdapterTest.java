package com.marketplace.payments;

import com.marketplace.shared.api.PaymentIntentDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentIntentLookupAdapterTest {

    @Mock
    private PaymentIntentRepository repository;

    @InjectMocks
    private com.marketplace.payments.spi.PaymentIntentLookupAdapter adapter;

    @Test
    void findById_returnsDetails() {
        UUID id = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent(id, bookingId, consumerId, 5000L, null);

        when(repository.findById(id)).thenReturn(Optional.of(intent));

        Optional<PaymentIntentDetails> result = adapter.findById(id);

        assertTrue(result.isPresent());
        assertEquals(id, result.get().paymentIntentId());
        assertEquals(bookingId, result.get().bookingId());
        assertEquals(consumerId, result.get().consumerId());
        assertEquals("CREATED", result.get().status());
    }

    @Test
    void findById_returnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        Optional<PaymentIntentDetails> result = adapter.findById(id);

        assertTrue(result.isEmpty());
    }

    @Test
    void findAllById_resolvesTheWindowInOneRoundTrip() {
        // The V229 reconciliation's batch read (CodeRabbit round 1 on #630):
        // one repository round trip for the whole window — JpaRepository's
        // standard findAllById — with absent ids simply missing.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID absent = UUID.randomUUID();
        PaymentIntent one = new PaymentIntent(first, null, UUID.randomUUID(), 5000L, null);
        PaymentIntent two = new PaymentIntent(second, null, UUID.randomUUID(), 3000L, null);

        when(repository.findAllById(java.util.List.of(first, second, absent)))
                .thenReturn(java.util.List.of(one, two));

        java.util.List<PaymentIntentDetails> result =
                adapter.findAllById(java.util.List.of(first, second, absent));

        assertEquals(2, result.size());
        assertEquals(first, result.get(0).paymentIntentId());
        assertEquals(second, result.get(1).paymentIntentId());
        verify(repository).findAllById(java.util.List.of(first, second, absent));
    }
}
