package com.marketplace.community;

import com.marketplace.shared.api.ContentModeratedEvent;
import com.marketplace.shared.api.ContentReportResolvedEvent;
import com.marketplace.shared.api.ReviewLookupPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * B-19 (compliance plan C.11 — محرك قواعد إشراف تلقائية): the
 * automatic moderation engine — DATA rules standing above the existing
 * reports machine (L45), evaluated INSIDE the report-creation
 * transaction. The one integration point is a single call
 * ({@code createReport} → {@link #evaluate(ContentReport)}, the CR-11
 * row): the new report lands, and before the caller's transaction
 * commits the engine asks the rules whether the community's signal
 * just crossed a line — so the report's creation, the content's hide,
 * the queue's drain and the reporters' adjudication facts are ONE unit
 * of work (the Data JPA transactions reference's own atomicity; the
 * C.11 mandate verbatim).
 *
 * <p><b>The measured condition (the rule's own axes):</b> a live
 * ENABLED rule on the report's {@code (targetType, reason)} pair,
 * whose threshold is reached by the DISTINCT live OPEN reporters on
 * the same target — the V64 partial unique (one live report per
 * reporter+target) makes the OPEN report count on a target EXACTLY its
 * distinct reporter count, so the count query is the community's
 * signal, measured. No rule, or below the threshold: the engine is a
 * no-op — the report simply waits for the human queue, exactly as
 * before.
 *
 * <p><b>The action (the machine's single measured verb,
 * {@code HIDE_CONTENT} — the same semantics the human resolve's own
 * hide carries):</b> the reported content hides (a POST flips
 * {@code HIDDEN_BY_MODERATOR}, a COMMENT takes the house soft delete,
 * a REVIEW rides the reviews module's port INSIDE this same
 * transaction — the one-unit-of-work rule), the author's alert fires
 * ({@code ContentModeratedEvent} on the REAL transition only — the
 * documented skip: an already-hidden or author-deleted target carries
 * no new fact, one real hide = one author alert), and EVERY live OPEN
 * report on the target resolves {@code RESOLVED} with the engine's own
 * note and {@code resolved_by = null} (the machine's fingerprint — a
 * person resolves with their id, the engine with its note), each
 * carrying the reporter's adjudication fact
 * ({@code ContentReportResolvedEvent} — the B-17 record, every
 * outcome fires it: the reporter's journey is «my report left the
 * queue», whichever way the verdict went — here the community's own
 * signal closed it).
 *
 * <p><b>Deliberately no {@code @Transactional} of its own:</b> the
 * engine NEVER opens a unit of work — it joins the caller's creation
 * transaction (the mandate). Equally deliberately no own
 * {@code @Observed}: the evaluation rides the creation command's own
 * span ({@code community.report.create}) exactly as the human
 * resolve's hide rides {@code community.report.resolve} — the automatic
 * action is a side effect of the report's creation, and the
 * moderation queue's own state (the resolved rows, the note, the
 * Envers trail) is the operators' visibility channel.
 */
@Service
public class ModerationRuleEngine {

    private final ModerationRuleRepository ruleRepository;
    private final ContentReportRepository reportRepository;
    private final NeighborhoodPostRepository postRepository;
    private final PostCommentRepository commentRepository;
    private final ReviewLookupPort reviewLookupPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ModerationRuleEngine(ModerationRuleRepository ruleRepository,
                                ContentReportRepository reportRepository,
                                NeighborhoodPostRepository postRepository,
                                PostCommentRepository commentRepository,
                                ReviewLookupPort reviewLookupPort,
                                ApplicationEventPublisher eventPublisher,
                                Clock clock) {
        this.ruleRepository = ruleRepository;
        this.reportRepository = reportRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.reviewLookupPort = reviewLookupPort;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * The creation hook — THE C.11 integration point (one call inside
     * {@code createReport}'s transaction, after the save, before the
     * return). A live enabled rule on the report's own pair, and the
     * distinct live OPEN reporters on the target at or past the
     * threshold: the automatic action fires, atomically with the
     * report's own creation. Anything else: a measured no-op.
     */
    public void evaluate(ContentReport newReport) {
        Optional<ModerationRule> rule = ruleRepository.findByTargetTypeAndReasonAndEnabledTrue(
                newReport.getTargetType(), newReport.getReason());
        if (rule.isEmpty()) {
            return;
        }
        // The rule's own condition — the signal on the rule's OWN axes: the
        // live OPEN reports of THIS reason on THIS target (the V64 partial
        // unique makes the count EXACTLY the distinct reporters — one live
        // report per reporter+target), the just-created report included
        // (same transaction — the auto-flush before the query).
        long openReports = reportRepository.count(reportsOnTarget(
                newReport.getTargetType(), newReport.getTargetId(), newReport.getReason()));
        if (openReports >= rule.get().getThreshold()) {
            applyRule(rule.get(), newReport, openReports);
        }
    }

    /**
     * The automatic action — the rule fired. The hide, the author's
     * alert, the queue's drain and the reporters' facts in the caller's
     * ONE transaction: the content flip and every resolve land together
     * with the report's own creation or not at all.
     */
    private void applyRule(ModerationRule rule, ContentReport trigger, long distinctReporters) {
        hideTargetAndAlertAuthor(trigger);

        String note = "الإشراف الآلي — القاعدة " + rule.getId()
                + " (" + rule.getTargetType().name() + "/" + rule.getReason().name()
                + ") عند " + distinctReporters + " مُبلِّغًا متمايزًا";
        // The action's drain scope — every live OPEN report on the target,
        // ALL reasons alike: the content is hidden, so every one of its
        // reports leaves the queue (the reporter's journey closes whichever
        // way they worded it).
        List<ContentReport> openReports = reportRepository.findAll(openReportsOnTarget(
                trigger.getTargetType(), trigger.getTargetId()));
        for (ContentReport report : openReports) {
            report.resolve(ReportStatus.RESOLVED, note, null, clock);
            reportRepository.save(report);
            eventPublisher.publishEvent(new ContentReportResolvedEvent(
                    report.getId(), report.getReporterId(),
                    report.getTargetType().name(), report.getTargetId(),
                    ReportStatus.RESOLVED.name()));
        }
    }

    /**
     * The engine's CONDITION axis — the live OPEN reports of ONE reason
     * on ONE target (the rule's own pair), composed through the
     * repository's own
     * {@link org.springframework.data.jpa.repository.JpaSpecificationExecutor}
     * arm (the same official channel the administrative queue's own
     * filter axis rides): a pure composition over the report's own
     * fields, with Hibernate's {@code @SoftDelete} live-row filter
     * applied by the machinery — exactly the discipline the duplicate
     * gate's lookup rides. The V64 partial unique (one live report per
     * reporter+target) makes this count EXACTLY the distinct reporters
     * carrying THIS reason on THIS target.
     */
    private static Specification<ContentReport> reportsOnTarget(
            ReportTargetType targetType, UUID targetId, ReportReason reason) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("targetType"), targetType),
                cb.equal(root.get("targetId"), targetId),
                cb.equal(root.get("reason"), reason),
                cb.equal(root.get("status"), ReportStatus.OPEN));
    }

    /**
     * The engine's ACTION axis — every live OPEN report on the target,
     * ALL reasons alike (the drain scope once the content hides).
     */
    private static Specification<ContentReport> openReportsOnTarget(
            ReportTargetType targetType, UUID targetId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("targetType"), targetType),
                cb.equal(root.get("targetId"), targetId),
                cb.equal(root.get("status"), ReportStatus.OPEN));
    }

    /**
     * The hide verb — the SAME measured semantics the human resolve's
     * own private path carries (L45), pinned by both suites: the real
     * transition only, per the target type, the author's alert riding
     * it. The documented skip is symmetric with the human path: an
     * already-hidden or author-deleted target carries no new fact — no
     * second flip, no duplicate alert — while the queue drains honestly
     * either way. A future target-type widening (a fourth
     * {@code ReportTargetType}) widens BOTH this switch and the resolve
     * path's — the CHECK pair, the enum and the creation gates with
     * them.
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
            // The review hide runs INSIDE this transaction through the reviews
            // module's port — the PUBLISHED->HIDDEN flip answers the review's
            // author on a real flip only (the documented skip).
            case REVIEW -> reviewLookupPort.hideAsModerator(report.getTargetId())
                    .ifPresent(authorId -> eventPublisher.publishEvent(new ContentModeratedEvent(
                            authorId, ReportTargetType.REVIEW.name(), report.getTargetId())));
        }
    }
}
