package com.marketplace.delivery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssignCourierRequest(
        @NotBlank @Size(max = 120) String courierName,
        @Size(max = 80) String courierPhone) {
}
