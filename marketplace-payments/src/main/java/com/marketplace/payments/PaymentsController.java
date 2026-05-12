package com.marketplace.payments;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.PAYMENTS, version = "1.0")
public class PaymentsController {

    private final PaymentsService paymentsService;
    private final CurrentUserProvider currentUserProvider;

    public PaymentsController(PaymentsService paymentsService, CurrentUserProvider currentUserProvider) {
        this.paymentsService = paymentsService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/intents/{id}")
    public ResponseEntity<PaymentIntentResponse> getIntent(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(PaymentIntentResponse.from(paymentsService.getIntentForUser(id, authentication)));
    }

    @PostMapping("/intents")
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<PaymentIntentResponse> createIntent(@Valid @RequestBody CreateIntentRequest request,
                                                              Authentication authentication) {
        UUID consumerId = currentUserProvider.getCurrentUserId(authentication);
        PaymentIntent intent = paymentsService.createIntent(
                request.bookingId(), consumerId, request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentIntentResponse.from(intent));
    }

    @PostMapping("/intents/{id}/process")
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<PaymentIntentResponse> processIntent(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(PaymentIntentResponse.from(paymentsService.processIntent(id, authentication)));
    }

    @PostMapping("/intents/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentIntentResponse> confirmIntent(@PathVariable UUID id,
                                                               @Valid @RequestBody ConfirmIntentRequest request) {
        return ResponseEntity.ok(PaymentIntentResponse.from(paymentsService.confirmIntent(id, request.externalId())));
    }

    @PostMapping("/intents/{id}/cancel")
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<PaymentIntentResponse> cancelIntent(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(PaymentIntentResponse.from(paymentsService.cancelIntent(id, authentication)));
    }

    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(PaymentResponse.from(paymentsService.refundPayment(paymentId)));
    }

    @PostMapping("/webhooks/{provider}")
    public ResponseEntity<Void> webhook(@PathVariable String provider,
                                        @RequestParam String eventId,
                                        @RequestParam String eventType,
                                        @RequestHeader(value = "X-Webhook-Signature", required = false) String signature) {
        boolean created = paymentsService.processWebhookEvent(provider, eventId, eventType, signature);
        return created ? ResponseEntity.accepted().build() : ResponseEntity.ok().build();
    }

    public record CreateIntentRequest(
            @NotNull UUID bookingId,
            String idempotencyKey
    ) {
    }

    public record ConfirmIntentRequest(String externalId) {
    }
}
