package com.marketplace.delivery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelDeliveryRequest(@NotBlank @Size(max = 500) String reason) { }
