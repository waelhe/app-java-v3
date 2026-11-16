package com.marketplace.shared.api;
import java.util.UUID;
/** The delivery domain's payment contract; payment execution/refunds remain owned by marketplace-payments. */
public interface DeliveryPaymentPort {
    PaymentIntentDetails createForDelivery(UUID deliveryRequestId, UUID requesterId, long amountMinor, String currency);
    void cancelOrRefund(UUID deliveryRequestId);
}
