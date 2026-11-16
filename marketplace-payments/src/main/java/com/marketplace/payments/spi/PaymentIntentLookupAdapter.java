package com.marketplace.payments.spi;

import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentIntentRepository;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional(readOnly = true)
public class PaymentIntentLookupAdapter implements PaymentIntentLookupPort {

    /**
     * Row 41 (agent findings 2026-10-26T13:11): the checkout leg's payment
     * proof — the settled family is exactly the collected-money family the
     * order auto-refund edge switches on (SUCCEEDED and the refunded
     * states that imply the money landed). CANCELLATION_PENDING is
     * deliberately OUT: the remote capture may still win or lose that
     * race — it is not verified payment yet.
     */
    private static final java.util.Set<com.marketplace.payments.PaymentIntentStatus> SETTLED =
            java.util.EnumSet.of(
                    com.marketplace.payments.PaymentIntentStatus.SUCCEEDED,
                    com.marketplace.payments.PaymentIntentStatus.PARTIALLY_REFUNDED,
                    com.marketplace.payments.PaymentIntentStatus.REFUNDED);

    private final PaymentIntentRepository paymentIntentRepository;

    public PaymentIntentLookupAdapter(PaymentIntentRepository paymentIntentRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
    }

    @Override
    public Optional<PaymentIntentDetails> findById(UUID paymentIntentId) {
        return paymentIntentRepository.findById(paymentIntentId).map(PaymentIntentLookupAdapter::toDetails);
    }

    @Override
    public List<PaymentIntentDetails> findAllById(Collection<UUID> paymentIntentIds) {
        // The V229 reconciliation's batch read (CodeRabbit round 1 on #630,
        // verified real): one repository round trip for the whole window —
        // JpaRepository.findAllById is the standard batch by primary key.
        return paymentIntentRepository.findAllById(paymentIntentIds).stream()
                .map(PaymentIntentLookupAdapter::toDetails)
                .toList();
    }

    @Override
    public boolean isOrderPayableSettled(UUID orderId) {
        return paymentIntentRepository.findByOrderId(orderId)
                .map(intent -> SETTLED.contains(intent.getStatus()))
                .orElse(false);
    }

    private static PaymentIntentDetails toDetails(PaymentIntent i) {
        return new PaymentIntentDetails(i.getId(), i.getBookingId(), i.getConsumerId(), i.getAdCampaignId(),
                i.getStatus().name(), i.getOrigin(), i.getAmountCents(), i.getCurrency(), i.getOrderId(),
                i.getLoanId(), i.getCommissionRate(), i.getDeliveryRequestId(), i.getRefundedAmountCents());
    }
}
