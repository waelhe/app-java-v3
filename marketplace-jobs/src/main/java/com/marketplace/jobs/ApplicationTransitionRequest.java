package com.marketplace.jobs;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * B-12 (compliance plan C.2): the application move body — the target
 * status only ({@code LeadTransitionRequest} shape).
 */
public record ApplicationTransitionRequest(
        @NotNull
        @Schema(description = "The target status: REVIEWED, ACCEPTED or REJECTED.")
        ApplicationStatus status
) {
}
