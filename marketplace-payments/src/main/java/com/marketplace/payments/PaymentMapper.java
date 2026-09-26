package com.marketplace.payments;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    /**
     * Base mapping: currency is intentionally absent from the Payment entity
     * (it lives on the intent that created the payment) — the refund-path
     * overload fills it, the same base-plus-overload shape
     * {@link PaymentIntentMapper} uses for {@code clientSecret}.
     */
    @Mapping(target = "currency", ignore = true)
    PaymentResponse toResponse(Payment payment);

    /** The refund surface — the payment with its intent's currency. */
    default PaymentResponse toResponse(PaymentsService.RefundedPayment result) {
        PaymentResponse base = toResponse(result.payment());
        return new PaymentResponse(
                base.id(), base.amountCents(), result.intent().getCurrency(),
                base.status(), base.createdAt(), base.updatedAt());
    }
}
