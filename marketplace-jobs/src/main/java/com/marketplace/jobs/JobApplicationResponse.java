package com.marketplace.jobs;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * B-12 (compliance plan C.2): the application's read model — the same
 * {@code from}-factory house shape; the employer's inbox and the
 * seeker's own list both read through it (one projection, two
 * ownerships — the read model carries no privilege of its own).
 */
public record JobApplicationResponse(
        @Schema(description = "The application's id.") UUID id,
        @Schema(description = "The applied job's id.") UUID jobId,
        @Schema(description = "The seeker's user id (users.id space).") UUID seekerId,
        @Schema(description = "The cover message to the employer.") String coverMessage,
        @Schema(description = "NEW, REVIEWED, ACCEPTED or REJECTED.") String status,
        @Schema(description = "The submission time.") Instant createdAt,
        @Schema(description = "The last-move time.") Instant updatedAt
) {

    public static JobApplicationResponse from(JobApplication application) {
        return new JobApplicationResponse(
                application.getId(),
                application.getJobId(),
                application.getSeekerId(),
                application.getCoverMessage(),
                application.getStatus().name(),
                application.getCreatedAt(),
                application.getUpdatedAt());
    }
}
