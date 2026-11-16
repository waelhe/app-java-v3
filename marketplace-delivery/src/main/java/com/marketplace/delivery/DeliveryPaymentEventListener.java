package com.marketplace.delivery;

import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class DeliveryPaymentEventListener {

    private final DeliveryService service;

    public DeliveryPaymentEventListener(DeliveryService s) {
        service = s;
    }

    @ApplicationModuleListener
    public void onPaymentStateChanged(PaymentStateChangedEvent e) {
        service.onPaymentStateChanged(e);
    }
}
