package com.marketplace.payments;

import org.springframework.stereotype.Component;

@Component
public class MockPaymentGatewayClient implements PaymentGatewayClient {
    @Override
    public GatewayPaymentResult process(PaymentIntent intent) {
        return new GatewayPaymentResult(true, "mock_" + intent.getId(), "Accepted by mock gateway");
    }
}
