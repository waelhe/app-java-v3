package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The moderation queue's read model (L45): the stored facts and nothing
 * else — the same projection discipline as the post and membership
 * views. The reporter, the moderated author (never exposed here — the
 * target's author is the target surface's own fact) and the resolving
 * admin all stay opaque UUIDs in the {@code users.id} space; the
 * identifiers and enums are their stored names, the resolution fields
 * are null until the one transition out of OPEN lands.
 */
public record ContentReportView(
        UUID id,
        UUID reporterId,
        String targetType,
        UUID targetId,
        String reason,
        String status,
        String resolutionNote,
        UUID resolvedBy,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt
) {
    static ContentReportView of(ContentReport report) {
        return new ContentReportView(
                report.getId(),
                report.getReporterId(),
                report.getTargetType().name(),
                report.getTargetId(),
                report.getReason().name(),
                report.getStatus().name(),
                report.getResolutionNote(),
                report.getResolvedBy(),
                report.getResolvedAt(),
                report.getCreatedAt(),
                report.getUpdatedAt());
    }
}
