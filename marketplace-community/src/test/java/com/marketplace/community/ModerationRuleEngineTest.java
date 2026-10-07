package com.marketplace.community;

import com.marketplace.shared.api.ContentModeratedEvent;
import com.marketplace.shared.api.ReviewLookupPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-19 (compliance plan C.11) — the automatic moderation engine's
 * contracts, unit-pinned (the DoD's own «شرط ← إجراء آلي»: condition →
 * automatic action):
 *
 * <ul>
 *   <li>the CONDITION: a live ENABLED rule on the report's own
 *       (targetType, reason) pair — the disabled-filtering lookup the
 *       engine rides — and the distinct live OPEN reporters on the
 *       target at or past the threshold (the just-created report
 *       included, the same transaction);</li>
 *   <li>the ACTION: the content's hide per the target type (POST
 *       flips HIDDEN_BY_MODERATOR, COMMENT takes the soft delete,
 *       REVIEW rides the port) with the author's
 *       ContentModeratedEvent on the REAL transition only — the
 *       documented skip symmetric with the human resolve path;</li>
 *   <li>the queue's drain: EVERY live OPEN report on the target
 *       resolves RESOLVED with the engine's own note and
 *       resolved_by = null (the machine's fingerprint), each carrying
 *       the reporter's ContentReportResolvedEvent (the B-17 record —
 *       every outcome fires it);</li>
 *   <li>the no-op: no rule, or below the threshold — the report
 *       simply waits for the human queue, exactly as before.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ModerationRuleEngineTest {

    private static final Instant FIXED = Instant.parse("2026-10-07T08:00:00Z");

    @Mock
    private ModerationRuleRepository ruleRepository;

    @Mock
    private ContentReportRepository reportRepository;

    @Mock
    private NeighborhoodPostRepository postRepository;

    @Mock
    private PostCommentRepository commentRepository;

    @Mock
    private ReviewLookupPort reviewLookupPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    private ModerationRuleEngine engine() {
        return new ModerationRuleEngine(ruleRepository, reportRepository, postRepository,
                commentRepository, reviewLookupPort, eventPublisher, clock);
    }

    private final UUID authorId = UUID.randomUUID();
    private final UUID reporterA = UUID.randomUUID();
    private final UUID reporterB = UUID.randomUUID();
    private final UUID reporterC = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final UUID commentId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();

    private ContentReport reportOn(UUID reporter, ReportTargetType type, UUID target, ReportReason reason) {
        return ContentReport.report(reporter, type, target, reason);
    }

    @SuppressWarnings("unchecked")
    private void theOpenCountIs(long count) {
        when(reportRepository.count(any(Specification.class))).thenReturn(count);
    }

    @Test
    void noLiveRule_isAMeasuredNoOp() {
        when(ruleRepository.findByTargetTypeAndReasonAndEnabledTrue(ReportTargetType.POST, ReportReason.SPAM))
                .thenReturn(Optional.empty());

        engine().evaluate(reportOn(reporterA, ReportTargetType.POST, postId, ReportReason.SPAM));

        // The rule lookup rides the ENABLED-filtering derived method (the
        // pause never reaches the engine), and nothing else runs: the
        // report waits for the human queue exactly as before.
        verify(ruleRepository).findByTargetTypeAndReasonAndEnabledTrue(ReportTargetType.POST, ReportReason.SPAM);
        verify(reportRepository, never()).count(any(Specification.class));
        verify(postRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void belowTheThreshold_isAMeasuredNoOp() {
        ModerationRule rule = ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 3);
        when(ruleRepository.findByTargetTypeAndReasonAndEnabledTrue(ReportTargetType.POST, ReportReason.SPAM))
                .thenReturn(Optional.of(rule));
        theOpenCountIs(2);

        engine().evaluate(reportOn(reporterA, ReportTargetType.POST, postId, ReportReason.SPAM));

        verify(reportRepository, never()).findAll(any(Specification.class));
        verify(postRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void atTheThreshold_theAutomaticActionFires_endToEnd() {
        ModerationRule rule = ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 3);
        when(ruleRepository.findByTargetTypeAndReasonAndEnabledTrue(ReportTargetType.POST, ReportReason.SPAM))
                .thenReturn(Optional.of(rule));
        theOpenCountIs(3);
        NeighborhoodPost post = NeighborhoodPost.post(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", clock);
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        ContentReport trigger = reportOn(reporterA, ReportTargetType.POST, postId, ReportReason.SPAM);
        ContentReport otherB = reportOn(reporterB, ReportTargetType.POST, postId, ReportReason.SPAM);
        ContentReport otherC = reportOn(reporterC, ReportTargetType.POST, postId, ReportReason.SPAM);
        when(reportRepository.findAll(any(Specification.class))).thenReturn(List.of(trigger, otherB, otherC));

        engine().evaluate(trigger);

        // The hide: the real VISIBLE -> HIDDEN_BY_MODERATOR flip + the save.
        assertThat(post.getStatus()).isEqualTo(PostStatus.HIDDEN_BY_MODERATOR);
        verify(postRepository).save(post);

        // The FULL event set of one automatic action: the author's ONE
        // ContentModeratedEvent (the real transition only) + the THREE
        // reporters' ContentReportResolvedEvent facts — four publications,
        // nothing more, nothing duplicated.
        ArgumentCaptor<Object> allEvents = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(4)).publishEvent(allEvents.capture());
        List<Object> published = allEvents.getAllValues();

        // The author's alert — exactly one, on the real transition.
        List<ContentModeratedEvent> alerts = published.stream()
                .filter(v -> v instanceof ContentModeratedEvent)
                .map(v -> (ContentModeratedEvent) v).toList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).recipientId()).isEqualTo(authorId);
        assertThat(alerts.get(0).targetType()).isEqualTo("POST");
        // The alert carries the post's OWN id (the looked-up row's id — in
        // the real flow the repository's answer IS the keyed row).
        assertThat(alerts.get(0).targetId()).isEqualTo(post.getId());

        // The queue's drain: EVERY open report on the target — the trigger
        // and the others alike — resolved RESOLVED with the engine's own
        // note and the machine's fingerprint (resolved_by = null).
        for (ContentReport resolved : List.of(trigger, otherB, otherC)) {
            assertThat(resolved.getStatus()).isEqualTo(ReportStatus.RESOLVED);
            assertThat(resolved.getResolvedBy()).isNull();
            assertThat(resolved.getResolvedAt()).isEqualTo(FIXED);
            assertThat(resolved.getResolutionNote())
                    .contains("الإشراف الآلي")
                    .contains(rule.getId().toString())
                    .contains("3");
        }
        verify(reportRepository).save(trigger);
        verify(reportRepository).save(otherB);
        verify(reportRepository).save(otherC);

        // The reporters' adjudication facts — one event per resolved report,
        // the B-17 record's own shape (the stored names, the outcome word).
        List<ContentReportResolvedEvent> facts = published.stream()
                .filter(v -> v instanceof ContentReportResolvedEvent)
                .map(v -> (ContentReportResolvedEvent) v).toList();
        assertThat(facts).hasSize(3);
        assertThat(facts).allSatisfy(event -> {
            assertThat(event.targetType()).isEqualTo("POST");
            assertThat(event.targetId()).isEqualTo(postId);
            assertThat(event.outcome()).isEqualTo("RESOLVED");
        });
        assertThat(facts).extracting(ContentReportResolvedEvent::reporterId)
                .containsExactlyInAnyOrder(reporterA, reporterB, reporterC);
    }

    @Test
    void belowTheThreshold_withMixedReasonsOnTheTarget_isAMeasuredNoOp() {
        // The rule watches SPAM on POSTs at threshold 3 while the target
        // carries mixed live OPEN reports (two SPAM + one HARASSMENT): the
        // community's SPAM signal is TWO, below the line. The unit pins the
        // below-threshold no-op; the REASON axis of the count spec — a
        // different reason never counting toward a rule it does not name —
        // is proven against the real schema (the JDBC validator's own count
        // query + the Docker-gated integration suite, the standing
        // "unit pins the contract, the real schema pins the query" split).
        ModerationRule rule = ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 3);
        when(ruleRepository.findByTargetTypeAndReasonAndEnabledTrue(ReportTargetType.POST, ReportReason.SPAM))
                .thenReturn(Optional.of(rule));
        theOpenCountIs(2);

        engine().evaluate(reportOn(reporterA, ReportTargetType.POST, postId, ReportReason.SPAM));

        verify(reportRepository, never()).findAll(any(Specification.class));
        verify(postRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void theCommentCase_takesTheSoftDeleteAndAlerts() {
        ModerationRule rule = ModerationRule.register(ReportTargetType.COMMENT, ReportReason.SPAM, 2);
        when(ruleRepository.findByTargetTypeAndReasonAndEnabledTrue(ReportTargetType.COMMENT, ReportReason.SPAM))
                .thenReturn(Optional.of(rule));
        theOpenCountIs(2);
        PostComment comment = PostComment.comment(postId, authorId, "تعليق");
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        ContentReport trigger = reportOn(reporterA, ReportTargetType.COMMENT, commentId, ReportReason.SPAM);
        when(reportRepository.findAll(any(Specification.class))).thenReturn(List.of(trigger));

        engine().evaluate(trigger);

        verify(commentRepository).delete(comment);
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues().stream()
                .filter(v -> v instanceof ContentModeratedEvent)
                .map(v -> ((ContentModeratedEvent) v).recipientId()))
                .containsExactly(authorId);
        assertThat(trigger.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    }

    @Test
    void theReviewCase_ridesThePortInsideTheTransaction() {
        ModerationRule rule = ModerationRule.register(ReportTargetType.REVIEW, ReportReason.INAPPROPRIATE, 2);
        when(ruleRepository.findByTargetTypeAndReasonAndEnabledTrue(ReportTargetType.REVIEW, ReportReason.INAPPROPRIATE))
                .thenReturn(Optional.of(rule));
        theOpenCountIs(2);
        when(reviewLookupPort.hideAsModerator(commentId)).thenReturn(Optional.of(authorId));
        ContentReport trigger = reportOn(reporterA, ReportTargetType.REVIEW, commentId, ReportReason.INAPPROPRIATE);
        when(reportRepository.findAll(any(Specification.class))).thenReturn(List.of(trigger));

        engine().evaluate(trigger);

        // The review's hide runs through the reviews module's port INSIDE
        // the caller's transaction (the one-unit-of-work rule).
        verify(reviewLookupPort).hideAsModerator(commentId);
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues().stream()
                .filter(v -> v instanceof ContentModeratedEvent)
                .map(v -> ((ContentModeratedEvent) v).targetType()))
                .containsExactly("REVIEW");
        assertThat(trigger.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    }

    @Test
    void theDocumentedSkip_alreadyHiddenTarget_noSecondAlert_butTheQueueStillDrains() {
        ModerationRule rule = ModerationRule.register(ReportTargetType.POST, ReportReason.SPAM, 2);
        when(ruleRepository.findByTargetTypeAndReasonAndEnabledTrue(ReportTargetType.POST, ReportReason.SPAM))
                .thenReturn(Optional.of(rule));
        theOpenCountIs(2);
        NeighborhoodPost hidden = NeighborhoodPost.post(authorId, locationId,
                PostCategory.GENERAL, "Title", "Body", clock);
        hidden.hideByModerator();
        when(postRepository.findById(postId)).thenReturn(Optional.of(hidden));
        ContentReport trigger = reportOn(reporterA, ReportTargetType.POST, postId, ReportReason.SPAM);
        when(reportRepository.findAll(any(Specification.class))).thenReturn(List.of(trigger));

        engine().evaluate(trigger);

        // One real hide = one author alert: the already-hidden target carries
        // no new fact — no second flip, no duplicate alert (the symmetric
        // documented skip) — while the queue drains honestly either way.
        verify(postRepository, never()).save(any());
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
        assertThat(trigger.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(trigger.getResolutionNote()).contains("الإشراف الآلي");
    }
}
