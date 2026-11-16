package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * The manager console's community-reports queue board — the cross-module
 * seam the web lever reads and moves through (the
 * {@code CommunityMembershipPort} house pattern verbatim: the interface
 * lives in shared-api, the community module implements it in its spi
 * package, the app root's queue board injects it — no module boundary is
 * crossed in code).
 */
public interface ContentReportQueuePort {

    /**
     * The open reports' actionable head: the OPEN reports on the
     * service's own documented drain order (the oldest open report
     * first — the queue's own clock), bounded by {@code limit} — the
     * deep history stays on the REST surface's deterministic
     * pagination.
     */
    List<ContentReportQueueEntry> openReportsQueue(int limit);

    /**
     * The ONE transition out of OPEN — the SAME {@code resolveReport}
     * service method the module's own REST admin controller calls (no
     * parallel write path): DISMISS closes the report as-is;
     * HIDE_CONTENT flips the content, alerts the author after commit,
     * and closes the report RESOLVED — the operator's note recorded
     * with the action. The service's own refusal vocabulary (409's
     * already-closed, 404's unknown row) surfaces unchanged.
     */
    void resolveReport(UUID adminId, UUID reportId, ReportResolution resolution, String note);
}
