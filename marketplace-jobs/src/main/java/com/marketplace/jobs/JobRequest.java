package com.marketplace.jobs;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * B-12 (compliance plan C.2): the posting body — the
 * {@code LeadRequest} house shape (a top-level validation-carrier record
 * the service receives directly).
 */
public record JobRequest(
        @NotBlank @Size(max = 200)
        @Schema(description = "The opening's title.", example = "مطلوب مصمم واجهات بدوام كامل")
        String title,
        @NotBlank @Size(max = 4000)
        @Schema(description = "The opening's description — responsibilities and requirements.")
        String description,
        @NotNull
        @Schema(description = "FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP or VOLUNTEER.")
        EmploymentType employmentType,
        @NotNull
        @Schema(description = "ONSITE, REMOTE or HYBRID.")
        WorkplaceType workplaceType,
        @NotBlank @Size(max = 100)
        @Schema(description = "The opening's city (e.g. الرياض).")
        String city,
        @Size(max = 100)
        @Schema(description = "The optional district inside the city.")
        String district,
        @Schema(description = "The optional salary floor in cents — present only as the all-or-nothing block.")
        Long salaryMinCents,
        @Schema(description = "The optional salary ceiling in cents — present only as the all-or-nothing block.")
        Long salaryMaxCents,
        @Size(min = 3, max = 3)
        @Schema(description = "ISO-4217 code — required exactly when either bound is present (SAR the home currency).", example = "SAR")
        String salaryCurrency,
        @Schema(description = "The optional application deadline — past it, applying answers 409.")
        Instant applicationDeadline
) {
}
