package com.marketplace.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * The moderation queue's own repository (L45). The
 * {@link JpaSpecificationExecutor} arm is the official Spring Data JPA
 * Specifications entry point — the administrative queue composes its
 * one filter axis (status, optional) through
 * {@link ContentReportSpecifications} on the complete FIFO sort key
 * {@code created_at ASC, id ASC} (a queue drains oldest-first — the
 * feed's DESC is a reader preference, the queue's ASC is the operator's
 * drain order; D-N5's determinism rule applies to both identically).
 *
 * <p>The duplicate lookup rides Hibernate's {@code @SoftDelete} filter
 * exactly as the V64 partial unique index does — only a LIVE report
 * blocks a re-report, so a purged/deleted report history never blocks a
 * fresh one on the same target. The RevisionRepository arm carries the
 * Envers trail (the V24 convention): every report creation and every
 * resolve flip is a revision the plan's criterion 6 reads.
 */
public interface ContentReportRepository
        extends JpaRepository<ContentReport, UUID>,
        JpaSpecificationExecutor<ContentReport>,
        RevisionRepository<ContentReport, UUID, Integer> {

    /** The duplicate gate's own lookup (one live report per reporter+target). */
    Optional<ContentReport> findByReporterIdAndTargetTypeAndTargetId(
            UUID reporterId, ReportTargetType targetType, UUID targetId);
}
