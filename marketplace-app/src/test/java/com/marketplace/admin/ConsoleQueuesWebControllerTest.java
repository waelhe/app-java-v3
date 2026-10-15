package com.marketplace.admin;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ContentReportQueuePort;
import com.marketplace.shared.api.InstitutionReviewQueuePort;
import com.marketplace.shared.api.NeighborhoodVerificationQueuePort;
import com.marketplace.shared.api.ReportResolution;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewModerationQueuePort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
 * every verdict is ONE port call (the SAME service method the REST admin
 * controllers reach through the module's own adapter — the shared-api
 * seam keeps the write path single), the post/redirect/get answer, and
 * the flash banner for every polite refusal face (an invalid action
 * word, 409's already-decided, 404's unknown row). The board reads ask
 * each queue for its actionable slice (PENDING / PENDING_REVIEW / OPEN)
 * — never the whole ledger.
 */
@ExtendWith(MockitoExtension.class)
class ConsoleQueuesWebControllerTest {

    @Mock
    private NeighborhoodVerificationQueuePort membershipQueue;

    @Mock
    private InstitutionReviewQueuePort institutionQueue;

    @Mock
    private ReviewModerationQueuePort moderationQueue;

    @Mock
    private ContentReportQueuePort reportQueue;

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
        when(membershipQueue.pendingVerificationQueue(ConsoleQueuesWebController.QUEUE_SLICE))
                .thenReturn(List.of());
        when(institutionQueue.pendingReviewQueue(ConsoleQueuesWebController.QUEUE_SLICE))
                .thenReturn(List.of());
        when(moderationQueue.pendingModerationQueue(ConsoleQueuesWebController.QUEUE_SLICE))
                .thenReturn(List.of());
        when(reportQueue.openReportsQueue(ConsoleQueuesWebController.QUEUE_SLICE))
                .thenReturn(List.of());

        assertThat(controller.queues(model)).isEqualTo("console/queues");

        // the model contract: the four actionable queues' bounded heads
        verify(model).addAttribute(eq("memberships"), any());
        verify(model).addAttribute(eq("institutions"), any());
        verify(model).addAttribute(eq("reviews"), any());
        verify(model).addAttribute(eq("reports"), any());
    }

    @Test
    void theMembershipVerdictIsOnePortCallWithAConfirmationBanner() {
        UUID id = UUID.randomUUID();

        RedirectView view = controller.reviewMembership(id, "APPROVE", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(membershipQueue).reviewVerification(id, true);
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("اعتُمد توثيق الجوار " + id));
    }

    @Test
    void anInvalidDecisionWordNeverReachesThePort() {
        RedirectView view = controller.reviewMembership(UUID.randomUUID(), "MAYBE", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_ERROR), any());
        verifyNoInteractions(membershipQueue);
    }

    @Test
    void anAlreadyDecidedMembershipAnswersTheConflictBanner() {
        UUID id = UUID.randomUUID();
        // the port's verdict is void-shaped — the doThrow form is Mockito's
        // own contract for stubbing it (when() needs a value to return)
        org.mockito.Mockito.doThrow(new ConflictException("already VERIFIED"))
                .when(membershipQueue).reviewVerification(id, false);

        RedirectView view = controller.reviewMembership(id, "REJECT", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_ERROR), any());
    }

    @Test
    void theInstitutionVerdictIsOnePortCallWithAConfirmationBanner() {
        UUID id = UUID.randomUUID();

        RedirectView view = controller.reviewInstitution(id, "APPROVE", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(institutionQueue).reviewInstitution(id, true);
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("اعتُمد توثيق المؤسسة " + id));
    }

    @Test
    void anUnknownInstitutionAnswersTheNotFoundBanner() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Institution", id))
                .when(institutionQueue).reviewInstitution(id, true);

        RedirectView view = controller.reviewInstitution(id, "APPROVE", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_ERROR), any());
    }

    @Test
    void theReviewApprovalIsOnePortCallWithAConfirmationBanner() {
        UUID id = UUID.randomUUID();

        RedirectView view = controller.moderateReview(id, "APPROVE", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(moderationQueue).approveReview(id);
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("نُشرت المراجعة " + id));
    }

    @Test
    void theReviewRejectionIsOnePortCallWithAConfirmationBanner() {
        UUID id = UUID.randomUUID();

        RedirectView view = controller.moderateReview(id, "REJECT", redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(moderationQueue).rejectReview(id);
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("أُخفيت المراجعة " + id));
    }

    @Test
    void theReportResolutionCarriesTheAdminIdentityAndTheNote() {
        UUID reportId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(adminId);

        RedirectView view = controller.resolveReport(reportId, "HIDE_CONTENT", "ملاحظة الإشراف", null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(reportQueue).resolveReport(adminId, reportId, ReportResolution.HIDE_CONTENT, "ملاحظة الإشراف");
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("أُخفي المحتوى وأُغلق البلاغ " + reportId));
    }

    @Test
    void theReportDismissCarriesTheNullNote() {
        UUID reportId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(adminId);

        RedirectView view = controller.resolveReport(reportId, "DISMISS", null, null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(reportQueue).resolveReport(adminId, reportId, ReportResolution.DISMISS, null);
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_SUCCESS), eq("جُرى تجاهل البلاغ " + reportId));
    }

    @Test
    void anInvalidResolutionWordAndAnOversizedNoteNeverReachThePort() {
        verifyNoInteractions(reportQueue);

        controller.resolveReport(UUID.randomUUID(), "DELETE", null, null, redirect);
        controller.resolveReport(UUID.randomUUID(), "DISMISS", "ن".repeat(2001), null, redirect);

        verify(redirect, org.mockito.Mockito.times(2))
                .addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_ERROR), any());
        verifyNoInteractions(reportQueue);
    }

    @Test
    void aClosedReportAnswersTheConflictBanner() {
        UUID reportId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new ConflictException("already RESOLVED"))
                .when(reportQueue).resolveReport(any(), eq(reportId), any(), isNull());

        RedirectView view = controller.resolveReport(reportId, "DISMISS", null, null, redirect);

        assertThat(view.getUrl()).isEqualTo("/console/queues");
        verify(redirect).addFlashAttribute(eq(ConsoleQueuesWebController.FLASH_ERROR), any());
    }
}
