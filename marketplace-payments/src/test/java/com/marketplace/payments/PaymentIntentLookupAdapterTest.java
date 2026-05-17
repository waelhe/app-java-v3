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
}
