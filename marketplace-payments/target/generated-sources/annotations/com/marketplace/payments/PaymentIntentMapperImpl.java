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
public class PaymentIntentMapperImpl implements PaymentIntentMapper {

    @Override
    public PaymentIntentResponse toResponse(PaymentIntent intent) {
        if ( intent == null ) {
            return null;
        }

        UUID id = null;
        UUID bookingId = null;
        Long amountCents = null;
        String currency = null;
        String status = null;
        String pspIntentId = null;
        Instant createdAt = null;
        Instant updatedAt = null;

        id = intent.getId();
        bookingId = intent.getBookingId();
        amountCents = intent.getAmountCents();
        currency = intent.getCurrency();
        if ( intent.getStatus() != null ) {
            status = intent.getStatus().name();
        }
        pspIntentId = intent.getPspIntentId();
        createdAt = intent.getCreatedAt();
        updatedAt = intent.getUpdatedAt();

        String clientSecret = null;

        PaymentIntentResponse paymentIntentResponse = new PaymentIntentResponse( id, bookingId, amountCents, currency, status, pspIntentId, clientSecret, createdAt, updatedAt );

        return paymentIntentResponse;
    }
}
