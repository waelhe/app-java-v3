package com.marketplace.community;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ContentModeratedEvent;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private ApplicationEventPublisher eventPublisher;

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
                commentRepository, eventPublisher, clock);
    }

    private NeighborhoodPost visiblePost(UUID author) {
        return NeighborhoodPost.post(author, locationId,
                PostCategory.GENERAL, "Title", "Body", clock);
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
        verify(eventPublisher, never()).publishEvent(any());
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
        ArgumentCaptor<ContentModeratedEvent> event =
                ArgumentCaptor.forClass(ContentModeratedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().recipientId()).isEqualTo(authorId);
        assertThat(event.getValue().targetType()).isEqualTo("POST");
        // the event rides the resolved post's OWN id — the honest fact the
        // notification message prints (in reality identical to the
        // report's targetId: findById can only return that row).
        assertThat(event.getValue().targetId()).isEqualTo(post.getId());
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
        ArgumentCaptor<ContentModeratedEvent> event =
                ArgumentCaptor.forClass(ContentModeratedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().recipientId()).isEqualTo(authorId);
        assertThat(event.getValue().targetType()).isEqualTo("COMMENT");
        // the event rides the resolved comment's OWN id (see the POST twin
        // above for the same reasoning).
        assertThat(event.getValue().targetId()).isEqualTo(comment.getId());
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
        // ... but no second write and no duplicate author alert
        verify(postRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
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
        verify(eventPublisher, never()).publishEvent(any());
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
