package com.marketplace.payments;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentsControllerTest {

    @Mock
    private PaymentsService paymentsService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private PaymentIntentMapper paymentIntentMapper;

    @InjectMocks
    private PaymentsController controller;

    @Test
    void getIntent_returnsIntent() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        PaymentIntentResponse response = new PaymentIntentResponse(id, UUID.randomUUID(), 5000L, "SAR", "CREATED", null, null, null, null);

        when(paymentsService.getIntentForUser(id, auth)).thenReturn(intent);
        when(paymentIntentMapper.toResponse(intent)).thenReturn(response);

        ResponseEntity<PaymentIntentResponse> result = controller.getIntent(id, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void createIntent_createsAndReturns201() {
        Authentication auth = mock(Authentication.class);
        UUID consumerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var request = new PaymentsController.CreateIntentRequest(bookingId, "key-1");
        PaymentIntent intent = PaymentIntent.create(bookingId, consumerId, 5000L, "key-1");
        PaymentIntentResponse response = new PaymentIntentResponse(UUID.randomUUID(), bookingId, 5000L, "SAR", "CREATED", null, null, null, null);

        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(consumerId);
        when(paymentsService.createIntent(bookingId, consumerId, "key-1")).thenReturn(intent);
        when(paymentIntentMapper.toResponse(intent)).thenReturn(response);

        ResponseEntity<PaymentIntentResponse> result = controller.createIntent(request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void processIntent_returnsProcessing() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        PaymentIntentResponse response = new PaymentIntentResponse(id, UUID.randomUUID(), 5000L, "SAR", "PROCESSING", null, null, null, null);

        when(paymentsService.processIntent(id, auth)).thenReturn(new PaymentsService.ProcessIntentResult(intent, null));
        when(paymentIntentMapper.toResponse(any(PaymentsService.ProcessIntentResult.class))).thenReturn(response);

        ResponseEntity<PaymentIntentResponse> result = controller.processIntent(id, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("PROCESSING", result.getBody().status());
    }

    @Test
    void confirmIntent_returnsSucceeded() {
        UUID id = UUID.randomUUID();
        var request = new PaymentsController.ConfirmIntentRequest("ext-1");
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        PaymentIntentResponse response = new PaymentIntentResponse(id, UUID.randomUUID(), 5000L, "SAR", "SUCCEEDED", null, null, null, null);

        when(paymentsService.confirmIntent(id, "ext-1")).thenReturn(intent);
        when(paymentIntentMapper.toResponse(intent)).thenReturn(response);

        ResponseEntity<PaymentIntentResponse> result = controller.confirmIntent(id, request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("SUCCEEDED", result.getBody().status());
    }

    @Test
    void cancelIntent_returnsCancelled() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        PaymentIntentResponse response = new PaymentIntentResponse(id, UUID.randomUUID(), 5000L, "SAR", "CANCELLED", null, null, null, null);

        when(paymentsService.cancelIntent(id, auth)).thenReturn(intent);
        when(paymentIntentMapper.toResponse(intent)).thenReturn(response);

        ResponseEntity<PaymentIntentResponse> result = controller.cancelIntent(id, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("CANCELLED", result.getBody().status());
    }

    @Test
    void refundPayment_returnsRefunded() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.create(UUID.randomUUID(), 5000L);
        PaymentResponse response = new PaymentResponse(paymentId, 5000L, "REFUNDED", null, null);

        when(paymentsService.refundPayment(paymentId)).thenReturn(payment);
        when(paymentMapper.toResponse(payment)).thenReturn(response);

        ResponseEntity<PaymentResponse> result = controller.refundPayment(paymentId);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("REFUNDED", result.getBody().status());
    }

    @Test
    void webhook_returnsOk() {
        boolean created = false;
        when(paymentsService.processWebhookEvent("stripe", "evt_1", "payment_intent.succeeded", "sig", null, null)).thenReturn(created);

        ResponseEntity<Void> result = controller.webhook("stripe", "evt_1", "payment_intent.succeeded", null, null, "sig");

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void webhook_returnsAcceptedWhenNew() {
        boolean created = true;
        when(paymentsService.processWebhookEvent("stripe", "evt_2", "payment_intent.succeeded", "sig", null, null)).thenReturn(created);

        ResponseEntity<Void> result = controller.webhook("stripe", "evt_2", "payment_intent.succeeded", null, null, "sig");

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
    }

    @Test
    void webhook_withPaymentIntentIdDispatches() {
        UUID intentId = UUID.randomUUID();
        boolean created = true;
        when(paymentsService.processWebhookEvent("stripe", "evt_3", "payment_intent.succeeded", "sig", intentId, "pi_123")).thenReturn(created);

        ResponseEntity<Void> result = controller.webhook("stripe", "evt_3", "payment_intent.succeeded", intentId, "pi_123", "sig");

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
    }
}
