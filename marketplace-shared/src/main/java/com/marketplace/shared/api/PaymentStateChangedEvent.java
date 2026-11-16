package com.marketplace.shared.api;

import java.util.UUID;

public record PaymentStateChangedEvent(UUID paymentIntentId, String state) {
}
