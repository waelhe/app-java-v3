package com.marketplace.payments;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PaymentIntentMapper {

    /**
     * Base mapping: clientSecret is intentionally absent from the entity (it
     * exists only in the remote-channel result of processIntent) — the
     * process-path overload fills it.
     */
    @Mapping(target = "clientSecret", ignore = true)
    PaymentIntentResponse toResponse(PaymentIntent intent);

    default PaymentIntentResponse toResponse(PaymentsService.ProcessIntentResult result) {
        PaymentIntentResponse base = toResponse(result.intent());
        if (result.clientSecret() == null) {
            return base;
        }
        return new PaymentIntentResponse(
                base.id(), base.bookingId(), base.amountCents(), base.currency(), base.status(),
                base.pspIntentId(), result.clientSecret(), base.createdAt(), base.updatedAt());
    }
}
