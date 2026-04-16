package com.marketplace.payments;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiConstants.PAYMENTS)
public class PaymentsController {

    private final PaymentsService paymentsService;
    private final SecurityUtils securityUtils;

    public PaymentsController(PaymentsService paymentsService, SecurityUtils securityUtils) {
        this.paymentsService = paymentsService;
        this.securityUtils = securityUtils;
    }

    @GetMapping("/intents/{id}")
    public ResponseEntity<PaymentIntent> getIntent(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentsService.getIntent(id));
    }

    @PostMapping("/intents")
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<PaymentIntent> createIntent(@Valid @RequestBody CreateIntentRequest request,
                                                       Authentication authentication) {
        UUID consumerId = securityUtils.getCurrentUserId(authentication);
        PaymentIntent intent = paymentsService.createIntent(
                request.bookingId(), consumerId,
                request.amountCents(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(intent);
    }

    @PostMapping("/intents/{id}/process")
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<PaymentIntent> processIntent(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentsService.processIntent(id));
    }

    @PostMapping("/intents/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentIntent> confirmIntent(@PathVariable UUID id,
                                                        @Valid @RequestBody ConfirmIntentRequest request) {
        return ResponseEntity.ok(paymentsService.confirmIntent(id, request.externalId()));
    }

    @PostMapping("/intents/{id}/cancel")
    @PreAuthorize("hasRole('CONSUMER')")
    public ResponseEntity<PaymentIntent> cancelIntent(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentsService.cancelIntent(id));
    }

    @PostMapping("/{paymentId}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Payment> refundPayment(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentsService.refundPayment(paymentId));
    }

    public record CreateIntentRequest(
            @NotNull UUID bookingId,
            @NotNull Long amountCents,
            String idempotencyKey
    ) {}

    public record ConfirmIntentRequest(String externalId) {}
}