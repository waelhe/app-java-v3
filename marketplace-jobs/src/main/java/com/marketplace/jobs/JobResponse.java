package com.marketplace.jobs;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * B-12 (compliance plan C.2): the job listing's read model — the
 * {@code NotificationResponse.from}/{@code ReviewResponse} house shape
 * (a record with the wire fields and a factory over the entity; the
 * entity itself never crosses the wire).
 */
public record JobResponse(
        @Schema(description = "The job's id.") UUID id,
        @Schema(description = "The employer's user id (users.id space — the A1 convention).") UUID employerId,
        @Schema(description = "The opening's title.") String title,
        @Schema(description = "The opening's description.") String description,
        @Schema(description = "FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP or VOLUNTEER.") String employmentType,
        @Schema(description = "ONSITE, REMOTE or HYBRID.") String workplaceType,
        @Schema(description = "The opening's city.") String city,
        @Schema(description = "The optional district inside the city.") String district,
        @Schema(description = "The optional salary floor in cents (null when undisclosed).") Long salaryMinCents,
        @Schema(description = "The optional salary ceiling in cents (null when undisclosed).") Long salaryMaxCents,
        @Schema(description = "The salary block's ISO-4217 code (null when undisclosed).") String salaryCurrency,
        @Schema(description = "ACTIVE or CLOSED.") String status,
        @Schema(description = "The optional application deadline.") Instant applicationDeadline,
        @Schema(description = "The row's creation time.") Instant createdAt,
        @Schema(description = "The row's last-update time.") Instant updatedAt
) {

    public static JobResponse from(JobListing job) {
        return new JobResponse(
                job.getId(),
                job.getEmployerId(),
                job.getTitle(),
                job.getDescription(),
                job.getEmploymentType().name(),
                job.getWorkplaceType().name(),
                job.getCity(),
                job.getDistrict(),
                job.getSalaryMinCents(),
                job.getSalaryMaxCents(),
                job.getSalaryCurrency(),
                job.getStatus().name(),
                job.getApplicationDeadline(),
                job.getCreatedAt(),
                job.getUpdatedAt());
    }
}
