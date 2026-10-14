package com.marketplace.admin;

import com.marketplace.community.ContentReportService;
import com.marketplace.community.MembershipVerificationState;
import com.marketplace.community.ModerationAction;
import com.marketplace.community.NeighborhoodMembershipService;
import com.marketplace.community.ReportStatus;
import com.marketplace.institutions.InstitutionService;
import com.marketplace.institutions.InstitutionVerificationState;
import com.marketplace.reviews.ReviewModerationStatus;
import com.marketplace.reviews.ReviewsService;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The queue boards' controller contract — the #570 wave's own discipline:
 * every verdict is ONE service call (the SAME method the REST admin
 * controller calls), the post/redirect/get answer, and the flash banner
 * for every polite refusal face (an invalid action word, 409's
 * already-decided, 404's unknown row). The board reads ask each queue
 * for its actionable slice (PENDING / PENDING_REVIEW / OPEN) — never the
 * whole ledger.
 */
@ExtendWith(MockitoExtension.class)
class ConsoleQueuesWebControllerTest {

    @Mock
    private NeighborhoodMembershipService membershipService;

    @Mock
    private InstitutionService institutionService;

    @Mock
    private ReviewsService reviewsService;

    @Mock
    private ContentReportService reportService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private Model model;

    @Mock
    private RedirectAttributes redirect;

    @InjectMocks
    private ConsoleQueuesWebController controller;

    @Test
    void theQueuesBoardAsksEveryQueueForItsActionableSlice() {
        when(membershipService.getVerificationQueue(eq(MembershipVerificationState.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(institutionService.reviewQueue(eq(InstitutionVerificationState.PENDING), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(reviewsService.moderationQueue(eq(ReviewModerationStatus.PENDING_REVIEW), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(reportService.getReports(eq(ReportStatus.OPEN), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(controller.queues(model)).isEqualTo("console/queues");

        // the model contract: the four actionable queues' bounded heads
        verify(model).addAttribute(eq("memberships"), any());
        verify(model).addAttribute(eq("institutions"), any());
        verify(model).addAttribute(eq("reviews"), any());
        verify(model).addAttribute(eq("reports"), any());
    }

    @Test
    void theMembershipVerdictIsOneServiceCallWithAConfirmationBanner() {
        UUID id = UUID.randomUUID();

        RedirectView view = controller.reviewMembership(id, "APPROVE", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(membershipService).reviewVerification(id, true);
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("اعتُمد توثيق الجوار " + id));
    }

    @Test
    void anInvalidDecisionWordNeverReachesTheService() {
        RedirectView view = controller.reviewMembership(UUID.randomUUID(), "MAYBE", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_ERROR), any());
        verifyNoInteractions(membershipService);
    }

    @Test
    void anAlreadyDecidedMembershipAnswersTheConflictBanner() {
        UUID id = UUID.randomUUID();
        when(membershipService.reviewVerification(id, false))
                .thenThrow(new ConflictException("already VERIFIED"));

        RedirectView view = controller.reviewMembership(id, "REJECT", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_ERROR), any());
    }

    @Test
    void theInstitutionVerdictIsOneServiceCallWithAConfirmationBanner() {
        UUID id = UUID.randomUUID();

        RedirectView view = controller.reviewInstitution(id, "APPROVE", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(institutionService).review(id, true);
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("اعتُمد توثيق المؤسسة " + id));
    }

    @Test
    void anUnknownInstitutionAnswersTheNotFoundBanner() {
        UUID id = UUID.randomUUID();
        when(institutionService.review(id, true))
                .thenThrow(new ResourceNotFoundException("Institution", id));

        RedirectView view = controller.reviewInstitution(id, "APPROVE", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_ERROR), any());
    }

    @Test
    void theReviewApprovalIsOneServiceCallWithAConfirmationBanner() {
        UUID id = UUID.randomUUID();

        RedirectView view = controller.moderateReview(id, "APPROVE", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(reviewsService).approve(id);
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("نُشرت المراجعة " + id));
    }

    @Test
    void theReviewRejectionIsOneServiceCallWithAConfirmationBanner() {
        UUID id = UUID.randomUUID();

        RedirectView view = controller.moderateReview(id, "REJECT", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(reviewsService).reject(id);
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("أُخفيت المراجعة " + id));
    }

    @Test
    void theReportResolutionCarriesTheAdminIdentityAndTheNote() {
        UUID reportId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(adminId);

        RedirectView view = controller.resolveReport(reportId, "HIDE_CONTENT", "ملاحظة الإشراف", null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(reportService).resolveReport(adminId, reportId, ModerationAction.HIDE_CONTENT, "ملاحظة الإشراف");
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("أُخفي المحتوى وأُغلق البلاغ " + reportId));
    }

    @Test
    void theReportDismissCarriesTheNullNote() {
        UUID reportId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(adminId);

        RedirectView view = controller.resolveReport(reportId, "DISMISS", null, null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(reportService).resolveReport(adminId, reportId, ModerationAction.DISMISS, null);
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("جُرى تجاهل البلاغ " + reportId));
    }

    @Test
    void anInvalidResolutionWordAndAnOversizedNoteNeverReachTheService() {
        verifyNoInteractions(reportService);

        controller.resolveReport(UUID.randomUUID(), "DELETE", null, null, redirect);
        controller.resolveReport(UUID.randomUUID(), "DISMISS", "ن".repeat(2001), null, redirect);

        verify(redirect, org.mockito.Mockito.times(2))
                .addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_ERROR), any());
        verifyNoInteractions(reportService);
    }

    @Test
    void aClosedReportAnswersTheConflictBanner() {
        UUID reportId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new ConflictException("already RESOLVED"))
                .when(reportService).resolveReport(any(), eq(reportId), any(), isNull());

        RedirectView view = controller.resolveReport(reportId, "DISMISS", null, null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_ERROR), any());
    }
}
