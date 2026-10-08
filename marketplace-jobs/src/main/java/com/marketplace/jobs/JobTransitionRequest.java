package com.marketplace.jobs;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * B-12 (compliance plan C.2): the close body — the target status only
 * ({@code LeadTransitionRequest} shape): anything outside the enum fails
 * bean validation with 400 before the service runs.
 */
public record JobTransitionRequest(
        @NotNull
        @Schema(description = "The target status: CLOSED.")
        JobStatus status
) {
}
