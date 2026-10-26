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

    // ------------------------------------------------------------------
    // Agent findings 2026-10-26T13:11 export, row 41 — the checkout leg's
    // payment proof: the settled family is the collected-money family
    // (SUCCEEDED + the refunded states that imply the money landed);
    // every unsettled state and the absent intent answer false.
    // ------------------------------------------------------------------

    @Test
    void isOrderPayableSettled_answersTrueForTheSettledFamily() {
        UUID orderId = UUID.randomUUID();
        PaymentIntent succeeded = new PaymentIntent(UUID.randomUUID(), null, UUID.randomUUID(), 5000L, null);
        succeeded.markProcessing();
        succeeded.markSucceeded();
        when(repository.findByOrderId(orderId)).thenReturn(Optional.of(succeeded));
        assertTrue(adapter.isOrderPayableSettled(orderId));

        PaymentIntent refunded = new PaymentIntent(UUID.randomUUID(), null, UUID.randomUUID(), 5000L, null);
        refunded.markProcessing();
        refunded.markSucceeded();
        refunded.markRefunded();
        when(repository.findByOrderId(orderId)).thenReturn(Optional.of(refunded));
        assertTrue(adapter.isOrderPayableSettled(orderId));
    }

    @Test
    void isOrderPayableSettled_answersFalseForUnsettledStatesAndTheAbsentIntent() {
        // CREATED and PROCESSING have not collected; FAILED and CANCELLED
        // never will; CANCELLATION_PENDING is the documented race — the
        // remote capture may still win or lose, it is not verified yet.
        UUID orderId = UUID.randomUUID();
        PaymentIntent created = new PaymentIntent(UUID.randomUUID(), null, UUID.randomUUID(), 5000L, null);
        when(repository.findByOrderId(orderId)).thenReturn(Optional.of(created));
        assertFalse(adapter.isOrderPayableSettled(orderId));

        PaymentIntent processing = new PaymentIntent(UUID.randomUUID(), null, UUID.randomUUID(), 5000L, null);
        processing.markProcessing();
        when(repository.findByOrderId(orderId)).thenReturn(Optional.of(processing));
        assertFalse(adapter.isOrderPayableSettled(orderId));

        PaymentIntent pendingRace = new PaymentIntent(UUID.randomUUID(), null, UUID.randomUUID(), 5000L, null);
        pendingRace.markProcessing();
        pendingRace.markCancellationPending();
        when(repository.findByOrderId(orderId)).thenReturn(Optional.of(pendingRace));
        assertFalse(adapter.isOrderPayableSettled(orderId));

        when(repository.findByOrderId(orderId)).thenReturn(Optional.empty());
        assertFalse(adapter.isOrderPayableSettled(orderId));
    }
}
