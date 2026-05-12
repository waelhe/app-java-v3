package com.marketplace.payments;

public interface PaymentGatewayClient {
    GatewayPaymentResult process(PaymentIntent intent);

    record GatewayPaymentResult(boolean accepted, String externalId, String message) {
    }
}
