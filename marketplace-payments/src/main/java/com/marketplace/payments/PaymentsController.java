package com.marketplace.payments;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.PAYMENTS, version = "1.0")
public class PaymentsController {

    private final PaymentsService paymentsService;
    private final CurrentUserProvider currentUserProvider;
    private final PaymentMapper paymentMapper;
    private final PaymentIntentMapper paymentIntentMapper;

    public PaymentsController(PaymentsService paymentsService, CurrentUserProvider currentUserProvider,
                              PaymentMapper paymentMapper, PaymentIntentMapper paymentIntentMapper) {
        this.paymentsService = paymentsService;
        this.currentUserProvider = currentUserProvider;
        this.paymentMapper = paymentMapper;
        this.paymentIntentMapper = paymentIntentMapper;
    }

    @GetMapping("/intents/{id}")
    @Operation(summary = "Read one's own payment intent",
            description = "The caller's payment intent by id — ownership-verified against the "
                    + "current user (the consumer who created it).")
    public ResponseEntity<PaymentIntentResponse> getIntent(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(paymentIntentMapper.toResponse(paymentsService.getIntentForUser(id, authentication)));
    }

    @PostMapping("/intents")
    @Operation(summary = "Create a payment intent for a booking",
            description = "Starts the money loop for a booking: the intent carries the booking's "
                    + "amount and ISO 4217 currency under the current consumer. The request "
                    + "body's idempotencyKey field is the caller's deduplication surface "
                    + "(a replay answers the existing intent). Answers 201.")
    public ResponseEntity<PaymentIntentResponse> createIntent(@Valid @RequestBody CreateIntentRequest request,
                                                              Authentication authentication) {
        UUID consumerId = currentUserProvider.getCurrentUserId(authentication);
        PaymentIntent intent = paymentsService.createIntent(
                request.bookingId(), consumerId, request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentIntentMapper.toResponse(intent));
    }

    @PostMapping("/intents/{id}/process")
    @Operation(summary = "Process a payment intent",
            description = "Drives the intent through the bound payment channel — with a real PSP "
                    + "this is the remote charge step (clientSecret in the answer); without "
                    + "one, the local-only path: the intent still advances to PROCESSING and a "
                    + "Payment row is written, with no remote charge. Ownership-verified "
                    + "against the caller.")
    public ResponseEntity<PaymentIntentResponse> processIntent(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(paymentIntentMapper.toResponse(paymentsService.processIntent(id, authentication)));
    }

    @PostMapping("/intents/{id}/confirm")
    @Operation(summary = "Confirm a payment intent (administrative)",
            description = "Closes the intent as captured with an external reference — the "
                    + "administrative completion surface of the money loop (service-level "
                    + "hasRole('ADMIN') gate); the automatic consumer-side completion rides "
                    + "the webhook channel instead.")
    public ResponseEntity<PaymentIntentResponse> confirmIntent(@PathVariable UUID id,
                                                               @Valid @RequestBody ConfirmIntentRequest request) {
        return ResponseEntity.ok(paymentIntentMapper.toResponse(paymentsService.confirmIntent(id, request.externalId())));
    }

    @PostMapping("/intents/{id}/cancel")
    @Operation(summary = "Cancel a payment intent",
            description = "Cancels the caller's intent when the state machine still allows it "
                    + "(a captured intent is not cancellable — refund is that exit). "
                    + "Ownership-verified against the caller.")
    public ResponseEntity<PaymentIntentResponse> cancelIntent(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(paymentIntentMapper.toResponse(paymentsService.cancelIntent(id, authentication)));
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Refund a captured payment",
            description = "Full refund of a captured payment through the refund state machine "
                    + "(service-level hasRole('ADMIN') gate); with a bound channel the remote "
                    + "refund is created with a derived idempotency key first.")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentMapper.toResponse(paymentsService.refundPayment(paymentId)));
    }

    @PostMapping("/webhooks/{provider}")
    @Operation(summary = "Receive a provider webhook notification",
            description = "The generic provider webhook channel — anonymous by contract "
                    + "(permitAll): authenticity comes from the X-Webhook-Signature HMAC, not "
                    + "a session. Deduplicated by eventId; answers 202 when a new payment "
                    + "resulted, 200 otherwise.")
    public ResponseEntity<Void> webhook(@PathVariable String provider,
                                        @RequestParam String eventId,
                                        @RequestParam String eventType,
                                        @RequestParam(required = false) UUID paymentIntentId,
                                        @RequestParam(required = false) String externalId,
                                        @RequestHeader(value = "X-Webhook-Signature", required = false) String signature) {
        boolean created = paymentsService.processWebhookEvent(provider, eventId, eventType, signature, paymentIntentId, externalId);
        return created ? ResponseEntity.accepted().build() : ResponseEntity.ok().build();
    }

    /**
     * Stripe's own webhook channel (roadmap B3): the raw request body plus the
     * Stripe-Signature header, exactly as the official verification recipe
     * prescribes ("requestBody: The request body string sent by Stripe,
     * signature: The Stripe-Signature header"). The body is read as raw bytes
     * and decoded UTF-8 so the signed payload reaches the verifier
     * byte-identical — a String converter would apply its own default charset
     * and could re-encode the body, breaking the signature. The 503 SU-001
     * answer when the channel is unbound is the house provider-gate
     * convention — the notification is never partially verified. Registered
     * by the existing "/api/v1/payments/webhooks/**" permitAll rule —
     * anonymous by contract: authenticity comes from the signature, not from
     * a session.
     */
    @PostMapping(path = "/webhooks/stripe", consumes = "application/json")
    @Operation(summary = "Receive a Stripe webhook event",
            description = "Stripe's own webhook channel (roadmap B3): the raw request body "
                    + "plus the Stripe-Signature header, byte-identical to what was signed. "
                    + "Answers 503 SU-001 while the channel is unbound — the notification is "
                    + "never partially verified. Anonymous by contract (permitAll): "
                    + "authenticity comes from the signature.")
    public ResponseEntity<Void> stripeWebhook(@RequestBody byte[] rawPayload,
                                              @RequestHeader("Stripe-Signature") String signatureHeader) {
        boolean created = paymentsService.handleStripeWebhook(
                new String(rawPayload, StandardCharsets.UTF_8), signatureHeader);
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
