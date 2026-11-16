package com.marketplace.delivery;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateDeliveryRequest(@NotNull UUID quoteId) {
}
