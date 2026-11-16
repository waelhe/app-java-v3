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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

import java.util.UUID;

/**
 * The manager console's queue boards — the four verification/moderation
 * queues the tri-partite catalog always NAMED (the surfaces that lived
 * REST-only, reachable with token tooling only), rendered as the
 * operator's web levers: the actionable slice of each queue with the
 * verdict forms that call the SAME service methods the REST admin
 * controllers call (no parallel write path, no new permissions — the
 * class-level {@code @PreAuthorize} carries the house vocabulary, and
 * every verdict method keeps the service's own method security on the
 * delegation).
 *
 * <p><b>Why this controller lives in the app module:</b> the queues are
 * cross-domain by nature (community's memberships and reports, reviews'
 * moderation, institutions' registry) — the composition root is the one
 * place that sees every module (the {@code AdminController} precedent:
 * the admin surface's own home). The console MODULE keeps its own
 * boards (flags/config/geo/planning — its own domain); these boards are
 * the app-root's view over the domains' own queues.</p>
 *
 * <p><b>The boundaries stay flat — the shared-api port seam:</b> every
 * queue arrives through its {@code shared.api} port (the
 * {@code CommunityMembershipPort} house pattern verbatim: the interface
 * lives in shared-api, the data owner implements it in its spi package,
 * this consumer injects it). No module dependency is crossed in code —
 * {@code ModulithVerificationTest} guards the seam, and the board rows
 * are shared records, never JPA entities — the controllers-touch-no-
 * entities rule holds on the web surface exactly as it holds on REST.
 * The admin module's own {@code allowedDependencies} is untouched
 * (shared-api was already on the list).</p>
 *
 * <p><b>The stack, verbatim in shape (the #566/#570 pattern):</b>
 * gs/serving-web-content's {@code @Controller} + {@code Model} +
 * Thymeleaf; gs/handling-form-submission's post/redirect/get; the flash
 * contract — every verdict answers a one-time Arabic banner on the
 * redirected board (a confirmation naming the verdict, or the polite
 * face of the service's own 409/404/400: an already-decided claim, an
 * unknown row, an invalid action word).</p>
 *
 * <p><b>The bounded slice:</b> each board renders the queue's FIRST
 * {@value #QUEUE_SLICE} entries on the service's own documented drain
 * order (the oldest pending claim first — the queues' own clock), not
 * the whole ledger: the operator's screen is the actionable head, and
 * the REST surface keeps its deterministic pagination for the deep
 * history.</p>
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class ConsoleQueuesWebController {

    /** The flash banner's error attribute — the boards' shared template contract. */
    static final String FLASH_ERROR = "consoleFlashError";

    /** The flash banner's confirmation attribute — the boards' shared template contract. */
    static final String FLASH_SUCCESS = "consoleFlashSuccess";

    /** The actionable head of each queue — the REST surface keeps the deep pagination. */
    static final int QUEUE_SLICE = 50;

    private final NeighborhoodVerificationQueuePort membershipQueue;
    private final InstitutionReviewQueuePort institutionQueue;
    private final ReviewModerationQueuePort moderationQueue;
    private final ContentReportQueuePort reportQueue;
    private final CurrentUserProvider currentUserProvider;

    public ConsoleQueuesWebController(NeighborhoodVerificationQueuePort membershipQueue,
                                      InstitutionReviewQueuePort institutionQueue,
                                      ReviewModerationQueuePort moderationQueue,
                                      ContentReportQueuePort reportQueue,
                                      CurrentUserProvider currentUserProvider) {
        this.membershipQueue = membershipQueue;
        this.institutionQueue = institutionQueue;
        this.moderationQueue = moderationQueue;
        this.reportQueue = reportQueue;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * The queues board — the four actionable queues: neighborhood
     * verification (PENDING), institution verification (PENDING), review
     * moderation (PENDING_REVIEW), and the community reports (OPEN).
     */
    @GetMapping("/console/queues")
    public String queues(Model model) {
        model.addAttribute("memberships", membershipQueue.pendingVerificationQueue(QUEUE_SLICE));
        model.addAttribute("institutions", institutionQueue.pendingReviewQueue(QUEUE_SLICE));
        model.addAttribute("reviews", moderationQueue.pendingModerationQueue(QUEUE_SLICE));
        model.addAttribute("reports", reportQueue.openReportsQueue(QUEUE_SLICE));
        return "console/queues";
    }

    /**
     * The neighborhood verification verdict — the verdict's ONLY mover in
     * both directions (APPROVE admits a PENDING claim and RE-ADMITS a
     * REJECTED one; REJECT refuses a PENDING claim), one service call
     * through the port seam.
     */
    @PostMapping("/console/queues/memberships/{membershipId}/review")
    public RedirectView reviewMembership(@PathVariable UUID membershipId,
                                         @RequestParam String decision,
                                         RedirectAttributes redirect) {
        boolean approve;
        try {
            approve = parseDecision(decision);
        } catch (BadRequestException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, ex.getMessage());
            return queuesBoard();
        }
        try {
            membershipQueue.reviewVerification(membershipId, approve);
            redirect.addFlashAttribute(FLASH_SUCCESS,
                    (approve ? "اعتُمد توثيق الجوار " : "رُفض توثيق الجوار ") + membershipId);
        } catch (ConflictException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, ex.getMessage());
        } catch (ResourceNotFoundException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, "لا طلب توثيق بهذا المعرّف: " + membershipId);
        }
        return queuesBoard();
    }

    /**
     * The institution verification verdict — the same both-directions
     * lever over the registry (APPROVE lands the trust mark, REJECT
     * refuses the PENDING claim), one service call through the port
     * seam.
     */
    @PostMapping("/console/queues/institutions/{institutionId}/review")
    public RedirectView reviewInstitution(@PathVariable UUID institutionId,
                                          @RequestParam String decision,
                                          RedirectAttributes redirect) {
        boolean approve;
        try {
            approve = parseDecision(decision);
        } catch (BadRequestException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, ex.getMessage());
            return queuesBoard();
        }
        try {
            institutionQueue.reviewInstitution(institutionId, approve);
            redirect.addFlashAttribute(FLASH_SUCCESS,
                    (approve ? "اعتُمد توثيق المؤسسة " : "رُفض توثيق المؤسسة ") + institutionId);
        } catch (ConflictException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, ex.getMessage());
        } catch (ResourceNotFoundException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, "لا مؤسسة بهذا المعرّف: " + institutionId);
        }
        return queuesBoard();
    }

    /**
     * The review moderation verdict — APPROVE publishes (the averages
     * recompute with it), REJECT hides (they recompute without it),
     * one service call each way through the port seam.
     */
    @PostMapping("/console/queues/reviews/{reviewId}/moderate")
    public RedirectView moderateReview(@PathVariable UUID reviewId,
                                       @RequestParam String action,
                                       RedirectAttributes redirect) {
        boolean approve;
        try {
            approve = parseDecision(action);
        } catch (BadRequestException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, ex.getMessage());
            return queuesBoard();
        }
        try {
            if (approve) {
                moderationQueue.approveReview(reviewId);
                redirect.addFlashAttribute(FLASH_SUCCESS, "نُشرت المراجعة " + reviewId);
            } else {
                moderationQueue.rejectReview(reviewId);
                redirect.addFlashAttribute(FLASH_SUCCESS, "أُخفيت المراجعة " + reviewId);
            }
        } catch (ConflictException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, ex.getMessage());
        } catch (ResourceNotFoundException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, "لا مراجعة بهذا المعرّف: " + reviewId);
        }
        return queuesBoard();
    }

    /**
     * The report resolution — the ONE transition out of OPEN, both ways
     * in one service call through the port seam: DISMISS closes the
     * report as-is; HIDE_CONTENT flips the content, alerts the author
     * after commit, and closes the report RESOLVED — the operator's
     * note recorded with the action.
     */
    @PostMapping("/console/queues/reports/{reportId}/resolve")
    public RedirectView resolveReport(@PathVariable UUID reportId,
                                      @RequestParam String action,
                                      @RequestParam(required = false) String note,
                                      Authentication authentication,
                                      RedirectAttributes redirect) {
        ReportResolution resolution;
        try {
            resolution = parseResolution(action);
        } catch (BadRequestException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, ex.getMessage());
            return queuesBoard();
        }
        if (note != null && note.length() > 2000) {
            redirect.addFlashAttribute(FLASH_ERROR, "الملاحظة حتى 2000 حرف — القيمة المرسلة أطول");
            return queuesBoard();
        }
        try {
            UUID adminId = currentUserProvider.getCurrentUserId(authentication);
            reportQueue.resolveReport(adminId, reportId, resolution, note);
            redirect.addFlashAttribute(FLASH_SUCCESS,
                    (resolution == ReportResolution.HIDE_CONTENT
                            ? "أُخفي المحتوى وأُغلق البلاغ " : "جُرى تجاهل البلاغ ") + reportId);
        } catch (ConflictException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, ex.getMessage());
        } catch (ResourceNotFoundException ex) {
            redirect.addFlashAttribute(FLASH_ERROR, "لا بلاغ بهذا المعرّف: " + reportId);
        }
        return queuesBoard();
    }

    // -------------------------------------------------------------------------
    // the shared PRG/parse helpers
    // -------------------------------------------------------------------------

    private RedirectView queuesBoard() {
        return new RedirectView("/console/queues", true, false);
    }

    /** The APPROVE/REJECT gate — the REST surfaces' own words. */
    private static boolean parseDecision(String raw) {
        return switch (raw == null ? "" : raw.trim()) {
            case "APPROVE" -> true;
            case "REJECT" -> false;
            default -> throw new BadRequestException(
                    "قرار غير صالح '" + raw + "' — القيم: APPROVE، REJECT");
        };
    }

    /** The report resolution gate — the REST surface's own words. */
    private static ReportResolution parseResolution(String raw) {
        return switch (raw == null ? "" : raw.trim()) {
            case "DISMISS" -> ReportResolution.DISMISS;
            case "HIDE_CONTENT" -> ReportResolution.HIDE_CONTENT;
            default -> throw new BadRequestException(
                    "قرار غير صالح '" + raw + "' — القيم: DISMISS، HIDE_CONTENT");
        };
    }
}
