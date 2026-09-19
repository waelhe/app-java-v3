package com.marketplace.community;

import org.springframework.data.jpa.domain.Specification;

/**
 * The official Spring Data JPA Specifications over {@link ContentReport}
 * (L45) — the same documented entry point the feed composes through
 * (D-N5's foundation; the administrative queue's one filter axis).
 *
 * <p>{@code hasStatus} is the queue's OPTIONAL filter — null is ABSENT
 * (the official Specifications model; {@code cb.equal} with a null
 * argument is not portable — the catalog's own CodeRabbit round-1
 * lesson, the same null discipline the feed's {@code hasCategory}
 * carries). Soft-deleted rows are excluded automatically by the entity's
 * {@code @SoftDelete} filter; the sort lives in the caller's
 * {@code Pageable}: the complete FIFO key {@code created_at ASC, id ASC}.
 */
public final class ContentReportSpecifications {

    private ContentReportSpecifications() {}

    /**
     * The queue's optional status predicate — null is ABSENT (the whole
     * queue, every state; the plan's {@code GET /api/v1/admin/reports?status=}).
     */
    public static Specification<ContentReport> hasStatus(ReportStatus status) {
        return (root, query, cb) -> status == null
                ? cb.conjunction()
                : cb.equal(root.get("status"), status);
    }
}
