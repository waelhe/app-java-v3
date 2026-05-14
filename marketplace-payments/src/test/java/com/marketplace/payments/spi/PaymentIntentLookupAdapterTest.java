package com.marketplace.payments.spi;

import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentIntentLookupAdapterTest {

    private final PaymentIntentRepository repository = mock(PaymentIntentRepository.class);
    private PaymentIntentLookupAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PaymentIntentLookupAdapter(repository);
    }

    @Test
    void findByIdReturnsDetails() {
        var id = UUID.randomUUID();
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        when(repository.findById(id)).thenReturn(Optional.of(intent));

        var result = adapter.findById(id);

        assertTrue(result.isPresent());
        assertEquals(intent.getId(), result.get().paymentIntentId());
        assertEquals(intent.getBookingId(), result.get().bookingId());
        assertEquals(intent.getConsumerId(), result.get().consumerId());
        assertEquals(intent.getStatus().name(), result.get().status());
    }

    @Test
    void findByIdReturnsEmptyWhenNotFound() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        var result = adapter.findById(id);

        assertTrue(result.isEmpty());
    }
}
