package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L45 — the report entity's own shapes, unit-pinned: the factory's
 * honest insert shape (OPEN, no resolution fields) and the ONE resolve
 * flip's stamps (status, note, resolver, timestamp through the injected
 * clock — the same seam every house domain timestamp rides).
 */
class ContentReportTest {

    private static final Instant FIXED = Instant.parse("2026-09-18T10:15:00Z");

    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    @Test
    void report_isOpenWithNoResolutionFields() {
        UUID reporterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        ContentReport report = ContentReport.report(reporterId,
                ReportTargetType.POST, targetId, ReportReason.SPAM);

        assertThat(report.getReporterId()).isEqualTo(reporterId);
        assertThat(report.getTargetType()).isEqualTo(ReportTargetType.POST);
        assertThat(report.getTargetId()).isEqualTo(targetId);
        assertThat(report.getReason()).isEqualTo(ReportReason.SPAM);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.OPEN);
        assertThat(report.getResolutionNote()).isNull();
        assertThat(report.getResolvedBy()).isNull();
        assertThat(report.getResolvedAt()).isNull();
    }

    @Test
    void resolve_stampsTheOutcomeNoteResolverAndTimestamp() {
        UUID adminId = UUID.randomUUID();
        ContentReport report = ContentReport.report(UUID.randomUUID(),
                ReportTargetType.COMMENT, UUID.randomUUID(), ReportReason.HARASSMENT);

        report.resolve(ReportStatus.RESOLVED, "Hidden per policy", adminId, clock);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(report.getResolutionNote()).isEqualTo("Hidden per policy");
        assertThat(report.getResolvedBy()).isEqualTo(adminId);
        assertThat(report.getResolvedAt()).isEqualTo(FIXED);
    }

    @Test
    void resolve_acceptsBothOutcomesFromOpen() {
        ContentReport dismissed = ContentReport.report(UUID.randomUUID(),
                ReportTargetType.POST, UUID.randomUUID(), ReportReason.OTHER);
        dismissed.resolve(ReportStatus.DISMISSED, null, UUID.randomUUID(), clock);
        assertThat(dismissed.getStatus()).isEqualTo(ReportStatus.DISMISSED);

        ContentReport resolved = ContentReport.report(UUID.randomUUID(),
                ReportTargetType.POST, UUID.randomUUID(), ReportReason.OTHER);
        resolved.resolve(ReportStatus.RESOLVED, null, UUID.randomUUID(), clock);
        assertThat(resolved.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    }
}
