package com.marketplace.jobs;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * B-12 (compliance plan C.2): the application body — the
 * {@code LeadRequest} house shape.
 */
public record JobApplicationRequest(
        @NotBlank @Size(max = 4000)
        @Schema(description = "The cover message to the employer.")
        String coverMessage
) {
}
