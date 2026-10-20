package com.marketplace.payments.spi;

import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentIntentMapper;
import com.marketplace.payments.PaymentIntentResponse;
import com.marketplace.payments.PaymentsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Buyer-owned payment-intent operations for transport adapters. PSP execution,
 * idempotency, webhook settlement and the order-confirmation event remain in
 * the payments module; GraphQL never handles provider webhooks or raw PSP data.
 */
@Component
public class PaymentIntentClientAdapter implements PaymentIntentClientPort {

    private final PaymentsService payments;
    private final PaymentIntentMapper mapper;

    public PaymentIntentClientAdapter(PaymentsService payments, PaymentIntentMapper mapper) {
        this.payments = payments;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntentClientView get(UUID paymentIntentId, Authentication authentication) {
        return toView(mapper.toResponse(payments.getIntentForUser(paymentIntentId, authentication)));
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntentClientView process(UUID paymentIntentId, Authentication authentication) {
        PaymentsService.ProcessIntentResult result = payments.processIntent(paymentIntentId, authentication);
        return toView(mapper.toResponse(result));
    }

    @Override
    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntentClientView cancel(UUID paymentIntentId, Authentication authentication) {
        return toView(mapper.toResponse(payments.cancelIntent(paymentIntentId, authentication)));
    }

    private static PaymentIntentClientView toView(PaymentIntentResponse response) {
        return new PaymentIntentClientView(response.id(), response.bookingId(),
                response.amountCents() == null ? null : Long.toString(response.amountCents()),
                response.currency(), response.status(), response.pspIntentId(), response.clientSecret(),
                response.createdAt(), response.updatedAt());
    }
}
