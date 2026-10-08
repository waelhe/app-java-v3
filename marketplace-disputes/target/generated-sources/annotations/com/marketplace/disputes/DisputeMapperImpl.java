package com.marketplace.disputes;

import java.time.Instant;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-10-08T00:06:31+0000",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
public class DisputeMapperImpl implements DisputeMapper {

    @Override
    public DisputeResponse toResponse(Dispute dispute) {
        if ( dispute == null ) {
            return null;
        }

        UUID id = null;
        UUID bookingId = null;
        UUID openedBy = null;
        DisputeStatus status = null;
        DisputeResolution resolution = null;
        UUID refundPaymentId = null;
        Long refundedAmountCents = null;
        String reason = null;
        Instant createdAt = null;
        Instant updatedAt = null;

        id = dispute.getId();
        bookingId = dispute.getBookingId();
        openedBy = dispute.getOpenedBy();
        status = dispute.getStatus();
        resolution = dispute.getResolution();
        refundPaymentId = dispute.getRefundPaymentId();
        refundedAmountCents = dispute.getRefundedAmountCents();
        reason = dispute.getReason();
        createdAt = dispute.getCreatedAt();
        updatedAt = dispute.getUpdatedAt();

        DisputeResponse disputeResponse = new DisputeResponse( id, bookingId, openedBy, status, resolution, refundPaymentId, refundedAmountCents, reason, createdAt, updatedAt );

        return disputeResponse;
    }
}
