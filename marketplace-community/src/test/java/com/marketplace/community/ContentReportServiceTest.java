package com.marketplace.community;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ContentModeratedEvent;
import com.marketplace.shared.api.ContentReportResolvedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L45 — the moderation service's gate orders, unit-pinned (the
 * integration test proves the whole chain against the real schema):
 *
 * <ul>
 *   <li>the creation gate order: VISIBLE-target resolve (the honest 404
 *       for unknown/hidden post, absent comment, or a comment under a
 *       hidden post) → own-content (409) → duplicate (409) → insert OPEN;</li>
 *   <li>the resolve command's atomicity carriers: HIDE_CONTENT flips the
 *       post, publishes ContentModeratedEvent and closes RESOLVED in the
 *       one command; DISMISS touches nothing but the report;</li>
 *   <li>the documented skip: an already-hidden target resolves RESOLVED
 *       with no second flip and no duplicate author alert (the one-real-
 *       hide-one-alert policy);</li>
 *   <li>closed history stays closed: a second resolve on a non-OPEN
 *       report answers 409.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ContentReportServiceTest {

    private static final Instant FIXED = Instant.parse("2026-09-18T10:15:00Z");

    @Mock
    private ContentReportRepository reportRepository;

    @Mock
    private NeighborhoodPostRepository postRepository;

    @Mock
    private PostCommentRepository commentRepository;

    @Mock
    private com.marketplace.shared.api.ReviewLookupPort reviewLookupPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ModerationRuleEngine moderationRuleEngine;

    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    private ContentReportService service;

    private UUID reporterId = UUID.randomUUID();
    private UUID authorId = UUID.randomUUID();
    private UUID adminId = UUID.randomUUID();
    private UUID locationId = UUID.randomUUID();
    private UUID postId = UUID.randomUUID();
    private UUID commentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentReportService(reportRepository, postRepository,
                commentRepository, reviewLookupPort, eventPublisher, clock,
                moderationRuleEngine);
    }

    private NeighborhoodPost visiblePost(UUID author) {
        return NeighborhoodPost.post(author, locationId,
                PostCategory.GENERAL, "Title", "Body", false, clock);
    }

    private ContentReport openPostReport() {
        return ContentReport.report(reporterId, ReportTargetType.POST, postId, ReportReason.SPAM);
    }

    // ---------- createReport: the target gate ----------

    @Test
    void createReport_unknownPost_isTheHonest404BeforeAnyWrite() {
        when(postRepository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createReport(reporterId,
                ReportTargetType.POST, postId, ReportReason.SPAM))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void createReport_hiddenPost_isTheHonest404() {
        NeighborhoodPost post = visiblePost(authorId);
        post.hideByModerator();
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> service.createReport(reporterId,
                ReportTargetType.POST, postId, ReportReason.SPAM))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void createReport_absentComment_isTheHonest404() {
        when(commentRepository.findById(commentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createReport(reporterId,
                ReportTargetType.COMMENT, commentId, ReportReason.HARASSMENT))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void createReport_commentUnderHiddenPost_isTheHonest404() {
        PostComment comment = PostComment.comment(postId, authorId, "Body");
        NeighborhoodPost parent = visiblePost(authorId);
        parent.hideByModerator();
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        when(postRepository.findById(postId)).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.createReport(reporterId,
                ReportTargetType.COMMENT, commentId, ReportReason.HARASSMENT))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reportRepository, never()).save(any());
    }

    // ---------- createReport: the own-content and duplicate gates ----------

    @Test
    void createReport_ownContent_is409BeforeAnyWrite() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(visiblePost(reporterId)));

        assertThatThrownBy(() -> service.createReport(reporterId,
                ReportTargetType.POST, postId, ReportReason.SPAM))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("own content");
        verify(reportRepository, never()).save(any());
    }

    @Test
    void createReport_duplicateLiveReport_is409() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(visiblePost(authorId)));
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetId(
                reporterId, ReportTargetType.POST, postId))
                .thenReturn(Optional.of(openPostReport()));

        assertThatThrownBy(() -> service.createReport(reporterId,
                ReportTargetType.POST, postId, ReportReason.SPAM))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
        verify(reportRepository, never()).save(any());
    }

    @Test
    void createReport_visibleTarget_insertsOpen() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(visiblePost(authorId)));
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetId(
                reporterId, ReportTargetType.POST, postId)).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentReportView view = service.createReport(reporterId,
                ReportTargetType.POST, postId, ReportReason.SPAM);

        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.reporterId()).isEqualTo(reporterId);
        assertThat(view.targetId()).isEqualTo(postId);
        assertThat(view.targetType()).isEqualTo("POST");
        assertThat(view.reason()).isEqualTo("SPAM");
        // B-19 wiring (the CodeRabbit-measured gap): the saved report rides
        // straight into the rule engine's evaluation — the automatic
        // moderation machine runs on every real creation, inside the same
        // command. A no-rule situation is the engine's own measured no-op
        // (its unit test pins that branch); this pin is the CALL itself.
        verify(moderationRuleEngine).evaluate(any(ContentReport.class));
    }

    // ---------- resolveReport ----------

    @Test
    void resolveReport_unknownReport_is404() {
        UUID reportId = UUID.randomUUID();
        when(reportRepository.findById(reportId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveReport(adminId, reportId,
                ModerationAction.DISMISS, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void resolveReport_alreadyClosed_is409() {
        ContentReport closed = openPostReport();
        closed.resolve(ReportStatus.DISMISSED, null, adminId, clock);
        UUID reportId = closed.getId();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.resolveReport(adminId, reportId,
                ModerationAction.DISMISS, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already DISMISSED");
    }

    @Test
    void resolveReport_dismiss_closesWithoutTouchingTheContent() {
        ContentReport report = openPostReport();
        UUID reportId = report.getId();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentReportView view = service.resolveReport(adminId, reportId,
                ModerationAction.DISMISS, "Not actionable");

        assertThat(view.status()).isEqualTo("DISMISSED");
        assertThat(view.resolutionNote()).isEqualTo("Not actionable");
        assertThat(view.resolvedBy()).isEqualTo(adminId);
        assertThat(view.resolvedAt()).isEqualTo(FIXED);
        verify(postRepository, never()).findById(any());
        verify(postRepository, never()).save(any());
        verify(commentRepository, never()).delete(any());
        // The dismiss never touches the content (no author alert) — but the
        // REPORTER's adjudication fact still publishes (the CodeRabbit round-1
        // adoption: every outcome fires it — «my report left the queue»,
        // whichever way the verdict went).
        ArgumentCaptor<ContentReportResolvedEvent> reporterFact =
                ArgumentCaptor.forClass(ContentReportResolvedEvent.class);
        verify(eventPublisher).publishEvent(reporterFact.capture());
        verify(eventPublisher, never())
                .publishEvent(org.mockito.ArgumentMatchers.any(ContentModeratedEvent.class));
        assertThat(reporterFact.getValue().reporterId()).isEqualTo(reporterId);
        assertThat(reporterFact.getValue().targetType()).isEqualTo("POST");
        assertThat(reporterFact.getValue().targetId()).isEqualTo(postId);
        assertThat(reporterFact.getValue().outcome()).isEqualTo("DISMISSED");
    }

    @Test
    void resolveReport_hideContent_flipsPublishesAndClosesInOneCommand() {
        ContentReport report = openPostReport();
        UUID reportId = report.getId();
        NeighborhoodPost post = visiblePost(authorId);
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(postRepository.findById(postId)).thenReturn(Optional.of(post));
        when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentReportView view = service.resolveReport(adminId, reportId,
                ModerationAction.HIDE_CONTENT, "Spam confirmed");

        assertThat(post.getStatus()).isEqualTo(PostStatus.HIDDEN_BY_MODERATOR);
        assertThat(view.status()).isEqualTo("RESOLVED");
        assertThat(view.resolvedBy()).isEqualTo(adminId);
        // Two publications ride the one command: the AUTHOR's hide alert and
        // the REPORTER's adjudication fact (the CodeRabbit round-1 adoption —
        // the human path now fires the reporter's event exactly as the
        // engine's automatic path does). Each captor matches its OWN event
        // type (Mockito's type-aware capture — measured both directions:
        // an untyped times(2) wanted two moderated events and failed with
        // "was 1"), so each verify pins exactly one publication of its kind.
        ArgumentCaptor<ContentModeratedEvent> event =
                ArgumentCaptor.forClass(ContentModeratedEvent.class);
        ArgumentCaptor<ContentReportResolvedEvent> reporterFact =
                ArgumentCaptor.forClass(ContentReportResolvedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        verify(eventPublisher).publishEvent(reporterFact.capture());
        assertThat(event.getAllValues().get(0).recipientId()).isEqualTo(authorId);
        assertThat(event.getAllValues().get(0).targetType()).isEqualTo("POST");
        // the event rides the resolved post's OWN id — the honest fact the
        // notification message prints (in reality identical to the
        // report's targetId: findById can only return that row).
        assertThat(event.getAllValues().get(0).targetId()).isEqualTo(post.getId());
        assertThat(reporterFact.getValue().reporterId()).isEqualTo(reporterId);
        assertThat(reporterFact.getValue().outcome()).isEqualTo("RESOLVED");
        assertThat(reporterFact.getValue().targetId()).isEqualTo(postId);
    }

    @Test
    void resolveReport_hideContentOnComment_softDeletesAndAlertsTheCommentAuthor() {
        ContentReport report = ContentReport.report(reporterId,
                ReportTargetType.COMMENT, commentId, ReportReason.HARASSMENT);
        UUID reportId = report.getId();
        PostComment comment = PostComment.comment(postId, authorId, "Body");
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentReportView view = service.resolveReport(adminId, reportId,
                ModerationAction.HIDE_CONTENT, null);

        assertThat(view.status()).isEqualTo("RESOLVED");
        verify(commentRepository).delete(comment);
        // Two publications ride the one command (the POST twin above for
        // the full reasoning): the author's hide alert + the reporter's
        // adjudication fact — one of EACH kind, pinned by its own
        // type-aware captor (see the POST twin for the measured basis).
        ArgumentCaptor<ContentModeratedEvent> event =
                ArgumentCaptor.forClass(ContentModeratedEvent.class);
        ArgumentCaptor<ContentReportResolvedEvent> reporterFact =
                ArgumentCaptor.forClass(ContentReportResolvedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        verify(eventPublisher).publishEvent(reporterFact.capture());
        assertThat(event.getAllValues().get(0).recipientId()).isEqualTo(authorId);
        assertThat(event.getAllValues().get(0).targetType()).isEqualTo("COMMENT");
        // the event rides the resolved comment's OWN id (see the POST twin
        // above for the same reasoning).
        assertThat(event.getAllValues().get(0).targetId()).isEqualTo(comment.getId());
        assertThat(reporterFact.getValue().outcome()).isEqualTo("RESOLVED");
        assertThat(reporterFact.getValue().reporterId()).isEqualTo(reporterId);
    }

    @Test
    void resolveReport_alreadyHiddenTarget_isTheDocumentedSkipNoFlipNoAlert() {
        ContentReport report = openPostReport();
        UUID reportId = report.getId();
        NeighborhoodPost alreadyHidden = visiblePost(authorId);
        alreadyHidden.hideByModerator();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(postRepository.findById(postId)).thenReturn(Optional.of(alreadyHidden));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentReportView view = service.resolveReport(adminId, reportId,
                ModerationAction.HIDE_CONTENT, null);

        // the report still drains honestly ...
        assertThat(view.status()).isEqualTo("RESOLVED");
        // ... but no second write and no duplicate author alert — the only
        // publication is the REPORTER's adjudication fact (the report did
        // leave the queue; the already-hidden target carries no new fact
        // for the author).
        verify(postRepository, never()).save(any());
        verify(eventPublisher, never())
                .publishEvent(org.mockito.ArgumentMatchers.any(ContentModeratedEvent.class));
        ArgumentCaptor<ContentReportResolvedEvent> reporterFact =
                ArgumentCaptor.forClass(ContentReportResolvedEvent.class);
        verify(eventPublisher).publishEvent(reporterFact.capture());
        assertThat(reporterFact.getValue().outcome()).isEqualTo("RESOLVED");
    }

    @Test
    void resolveReport_authorDeletedTarget_isTheDocumentedSkip() {
        ContentReport report = openPostReport();
        UUID reportId = report.getId();
        when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(postRepository.findById(postId)).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ContentReportView view = service.resolveReport(adminId, reportId,
                ModerationAction.HIDE_CONTENT, null);

        assertThat(view.status()).isEqualTo("RESOLVED");
        verify(postRepository, never()).save(any());
        // no author alert (the deleted target carries no alertable fact) —
        // the REPORTER's adjudication fact still publishes (the report
        // left the queue).
        verify(eventPublisher, never())
                .publishEvent(org.mockito.ArgumentMatchers.any(ContentModeratedEvent.class));
        verify(eventPublisher, org.mockito.Mockito.times(1)).publishEvent(
                org.mockito.ArgumentMatchers.any(ContentReportResolvedEvent.class));
    }

    // ---------- getReports ----------

    @Test
    void getReports_pinsTheCompleteFifoSortKey() {
        when(reportRepository.findAll(
                any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.getReports(ReportStatus.OPEN, PageRequest.of(0, 20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(reportRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt")
                        .and(Sort.by(Sort.Direction.ASC, "id")));
    }

    @Test
    void getReports_nullStatus_isTheWholeQueue() {
        when(reportRepository.findAll(
                any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.getReports(null, PageRequest.of(0, 20));

        // null status = the absent predicate (the conjunction — pinned in
        // ContentReportSpecificationsTest); the read still rides the
        // complete FIFO key.
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(reportRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                pageable.capture());
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt")
                        .and(Sort.by(Sort.Direction.ASC, "id")));
    }
}
