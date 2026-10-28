package com.marketplace.delivery;

import jakarta.validation.constraints.*;

public record CreateDeliveryQuoteRequest(
        @NotBlank @Size(max = 250) String pickupAddress,
        @DecimalMin("-90.0") @DecimalMax("90.0") double pickupLatitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") double pickupLongitude,
        @NotBlank @Size(max = 250) String dropoffAddress,
        @DecimalMin("-90.0") @DecimalMax("90.0") double dropoffLatitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") double dropoffLongitude,
        @NotBlank @Size(max = 500) String packageDescription) {
}
