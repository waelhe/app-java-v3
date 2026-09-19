package com.marketplace.community;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ContentModeratedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The moderation &amp; reports surface (neighborhood community plan
 * §5-L45). One creation command with three gates, one resolve command
 * that owns its whole action atomically, one queue read — all measured
 * from the house precedents:
 *
 * <p><b>The creation gate order (before any write):</b> the target
 * resolves as VISIBLE content first — an unknown, hidden or deleted
 * target is the honest 404 (the L42 {@code visiblePost} convention: a
 * hidden post's comments are absent exactly as the post itself is) —
 * then the own-content gate answers 409 ("التقرير على محتواك" — the
 * plan's own words), and the duplicate gate answers 409 with the V64
 * partial unique index as the backstop (the 23505⇒409 translation, the
 * L30 G-N1 "قيد + backstop" precedent verbatim). Membership is
 * deliberately NOT a gate — the plan's own reasoning: "المار على تغذية
 * حيه عضو أصلًا" (whoever can see the feed is a member already).
 *
 * <p><b>The resolve command's atomicity (the plan's criterion 2):</b>
 * {@code HIDE_CONTENT} flips the post to {@code HIDDEN_BY_MODERATOR} (or
 * soft-deletes the comment, per the target type), publishes
 * {@link ContentModeratedEvent} inside the same transaction, and closes
 * the report {@code RESOLVED} — one unit of work, one Envers revision
 * trail. The service-level {@code @PreAuthorize} is defense in depth
 * behind the controller's class-level gate (the GeoAdminController /
 * GeoService L30 pattern: chain rule + controller + service).
 *
 * <p><b>The documented skip:</b> an already-hidden post or an
 * author-deleted target at resolve time carries no new fact for the
 * author — the hide goal is already met — so the report still closes
 * {@code RESOLVED} but no second flip and no duplicate notification fire
 * (one real hide = one author alert; the queue drains honestly either
 * way).
 *
 * <p><b>The queue read (D-N5's determinism rule):</b> the complete FIFO
 * sort key {@code created_at ASC, id ASC} — a queue drains oldest-first
 * (the feed's DESC is a reader preference, the queue's ASC is the
 * operator's drain order); the one filter axis is the optional status.
 */
@Service
@Transactional
public class ContentReportService {

    /** The queue's complete drain order (D-N5): oldest first, id breaking ties. */
    private static final Sort QUEUE_SORT =
            Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final ContentReportRepository reportRepository;
    private final NeighborhoodPostRepository postRepository;
    private final PostCommentRepository commentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ContentReportService(ContentReportRepository reportRepository,
                                NeighborhoodPostRepository postRepository,
                                PostCommentRepository commentRepository,
                                ApplicationEventPublisher eventPublisher,
                                Clock clock) {
        this.reportRepository = reportRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Report a piece of content — the plan's queue intake. Gate order:
     * VISIBLE-target resolve (404) → own-content (409) → duplicate (409,
     * the index backstop) → insert OPEN. No membership gate, by the
     * plan's own words.
     */
    @Observed(name = "community.report.create")
    public ContentReportView createReport(UUID reporterId, ReportTargetType targetType,
                                          UUID targetId, ReportReason reason) {
        UUID targetAuthorId = resolveVisibleTargetAuthor(targetType, targetId);
        if (reporterId.equals(targetAuthorId)) {
            throw new ConflictException(
                    "Cannot report your own content — the report queue exists for others' content");
        }
        reportRepository.findByReporterIdAndTargetTypeAndTargetId(reporterId, targetType, targetId)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "A report on this " + targetType + " already exists for this reporter ("
                                    + existing.getId() + ")");
                });
        ContentReport saved = reportRepository.save(
                ContentReport.report(reporterId, targetType, targetId, reason));
        return ContentReportView.of(saved);
    }

    /**
     * The administrative resolve — the ONE transition out of OPEN. The
     * service-level ADMIN gate is defense in depth behind the
     * controller's class-level one (the L30 three-layer pattern); a
     * report that already left OPEN answers 409 — closed history stays
     * closed, the Envers trail keeps every flip (criterion 6).
     *
     * <p>{@code HIDE_CONTENT} is the plan's atomic action: the content
     * flip, the {@code CONTENT_MODERATED} event (inside this
     * transaction) and the {@code RESOLVED} close land together or not
     * at all.
     */
    @Observed(name = "community.report.resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ContentReportView resolveReport(UUID adminId, UUID reportId,
                                           ModerationAction action, String note) {
        ContentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", reportId));
        if (report.getStatus() != ReportStatus.OPEN) {
            throw new ConflictException("Report " + reportId + " is already "
                    + report.getStatus() + " — a closed report stays closed (the audit trail)");
        }
        switch (action) {
            case DISMISS -> report.resolve(ReportStatus.DISMISSED, note, adminId, clock);
            case HIDE_CONTENT -> {
                hideTargetAndAlertAuthor(report);
                report.resolve(ReportStatus.RESOLVED, note, adminId, clock);
            }
        }
        return ContentReportView.of(reportRepository.save(report));
    }

    /**
     * The administrative queue — the plan's {@code GET /api/v1/admin/reports
     * ?status=}: the optional status axis on the complete FIFO key.
     * Read-only by the house convention.
     */
    @Transactional(readOnly = true)
    public Page<ContentReportView> getReports(ReportStatus status, Pageable pageable) {
        Pageable queuePageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), QUEUE_SORT);
        return reportRepository.findAll(
                        ContentReportSpecifications.hasStatus(status), queuePageable)
                .map(ContentReportView::of);
    }

    /**
     * The HIDE_CONTENT side of the resolve, per the target type (the
     * plan: a POST flips to {@code HIDDEN_BY_MODERATOR}, a COMMENT takes
     * the house soft delete). The event rides the REAL transition only —
     * an already-hidden or author-deleted target is the documented skip:
     * the hide goal is already met, so no second flip and no duplicate
     * author alert.
     */
    private void hideTargetAndAlertAuthor(ContentReport report) {
        switch (report.getTargetType()) {
            case POST -> postRepository.findById(report.getTargetId())
                    .filter(post -> post.getStatus() == PostStatus.VISIBLE)
                    .ifPresent(post -> {
                        post.hideByModerator();
                        postRepository.save(post);
                        eventPublisher.publishEvent(new ContentModeratedEvent(
                                post.getAuthorId(), ReportTargetType.POST.name(), post.getId()));
                    });
            case COMMENT -> commentRepository.findById(report.getTargetId())
                    .ifPresent(comment -> {
                        commentRepository.delete(comment);
                        eventPublisher.publishEvent(new ContentModeratedEvent(
                                comment.getAuthorId(), ReportTargetType.COMMENT.name(),
                                comment.getId()));
                    });
        }
    }

    /**
     * The target gate — the L42 {@code visiblePost} convention extended
     * to both targets: an unknown, hidden or deleted post answers the
     * honest 404; a comment answers 404 when itself absent OR when its
     * parent post is not VISIBLE (a hidden post's comments are absent
     * exactly as the post itself is — the plan's criterion 5 wording).
     * Returns the target author's id — the own-content gate's fact.
     */
    private UUID resolveVisibleTargetAuthor(ReportTargetType targetType, UUID targetId) {
        return switch (targetType) {
            case POST -> visiblePost(targetId).getAuthorId();
            case COMMENT -> visibleCommentAuthor(targetId);
        };
    }

    /** The VISIBLE-post gate (the L42 read path's own, verbatim role). */
    private NeighborhoodPost visiblePost(UUID postId) {
        return postRepository.findById(postId)
                .filter(post -> post.getStatus() == PostStatus.VISIBLE)
                .orElseThrow(() -> new ResourceNotFoundException("Post", postId));
    }

    /**
     * The comment gate: the comment row itself, reached through the
     * parent post's VISIBLE gate — both absent rows answer the comment's
     * honest 404.
     */
    private UUID visibleCommentAuthor(UUID commentId) {
        PostComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        boolean parentVisible = postRepository.findById(comment.getPostId())
                .map(post -> post.getStatus() == PostStatus.VISIBLE)
                .orElse(false);
        if (!parentVisible) {
            throw new ResourceNotFoundException("Comment", commentId);
        }
        return comment.getAuthorId();
    }
}
