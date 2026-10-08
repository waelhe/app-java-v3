package com.marketplace.payments;

import java.time.Instant;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-10-08T00:35:19+0000",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
public class PaymentMapperImpl implements PaymentMapper {

    @Override
    public PaymentResponse toResponse(Payment payment) {
        if ( payment == null ) {
            return null;
        }

        UUID id = null;
        Long amountCents = null;
        String status = null;
        Instant createdAt = null;
        Instant updatedAt = null;

        id = payment.getId();
        amountCents = payment.getAmountCents();
        if ( payment.getStatus() != null ) {
            status = payment.getStatus().name();
        }
        createdAt = payment.getCreatedAt();
        updatedAt = payment.getUpdatedAt();

        String currency = null;

        PaymentResponse paymentResponse = new PaymentResponse( id, amountCents, currency, status, createdAt, updatedAt );

        return paymentResponse;
    }
}
