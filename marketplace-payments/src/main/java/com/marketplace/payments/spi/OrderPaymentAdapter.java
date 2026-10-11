package com.marketplace.payments.spi;

import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentsService;
import com.marketplace.shared.api.OrderPaymentPort;
import com.marketplace.shared.api.PaymentIntentDetails;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stage 6 (ADR-0002): the payments module's implementation of the
 * {@link OrderPaymentPort} cross-module contract — the orders module
 * reaches the EXISTING engine here (the {@code PaymentRefundAdapter}
 * pattern verbatim; no payment internals leak across the boundary).
 *
 * <p>The cancellation's money half is event-driven (the
 * {@code OrderCancelledEventListener} on {@code OrderCancelledEvent} →
 * {@link PaymentsService#autoRefundByOrder} — the
 * {@code autoRefundByBooking} twin), so the ORDER cancellation and the
 * booking cancellation share the ONE refund contract.
 */
@Component
public class OrderPaymentAdapter implements OrderPaymentPort {

    private final PaymentsService paymentsService;

    public OrderPaymentAdapter(PaymentsService paymentsService) {
        this.paymentsService = paymentsService;
    }

    @Override
    public PaymentIntentDetails createForOrder(UUID orderId, UUID consumerId,
                                               long amountMinor, String currency) {
        PaymentIntent intent = paymentsService.createOrderIntent(orderId, consumerId, amountMinor, currency);
        return new PaymentIntentDetails(intent.getId(), intent.getBookingId(), intent.getConsumerId(),
                intent.getAdCampaignId(), intent.getStatus().name(), intent.getOrigin(),
                intent.getAmountCents(), intent.getCurrency(), intent.getOrderId());
    }
}
