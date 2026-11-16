package com.marketplace.community.spi;

import com.marketplace.community.ContentReportService;
import com.marketplace.community.ModerationAction;
import com.marketplace.community.ReportStatus;
import com.marketplace.shared.api.ContentReportQueueEntry;
import com.marketplace.shared.api.ContentReportQueuePort;
import com.marketplace.shared.api.ReportResolution;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The community module's implementation of the
 * {@link ContentReportQueuePort} cross-module contract — the manager
 * console's reports board reads and resolves the open reports through
 * this seam, never through a module dependency (the
 * {@code CommunityMembershipAdapter} house pattern verbatim).
 *
 * <p><b>No parallel write path:</b> the resolution is the SAME
 * {@code resolveReport} service call the module's own REST admin
 * controller ({@code ModerationAdminController}) makes — the adapter
 * maps the boundary vocabulary ({@link ReportResolution}) onto the
 * module's own {@link ModerationAction} state machine and delegates;
 * the service's own transaction, observation
 * ({@code community.report.resolve}), and method-security contracts
 * apply unchanged to the web lever.</p>
 */
@Component
public class ContentReportQueueAdapter implements ContentReportQueuePort {

    private final ContentReportService reportService;

    public ContentReportQueueAdapter(ContentReportService reportService) {
        this.reportService = reportService;
    }

    @Override
    public List<ContentReportQueueEntry> openReportsQueue(int limit) {
        return reportService
                .getReports(ReportStatus.OPEN, PageRequest.of(0, limit))
                .map(view -> new ContentReportQueueEntry(
                        view.id(),
                        view.reporterId(),
                        view.targetType(),
                        view.targetId(),
                        view.reason(),
                        view.createdAt()))
                .getContent();
    }

    @Override
    public void resolveReport(UUID adminId, UUID reportId, ReportResolution resolution, String note) {
        reportService.resolveReport(adminId, reportId, switch (resolution) {
            case DISMISS -> ModerationAction.DISMISS;
            case HIDE_CONTENT -> ModerationAction.HIDE_CONTENT;
        }, note);
    }
}
