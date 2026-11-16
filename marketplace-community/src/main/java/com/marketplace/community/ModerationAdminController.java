package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * L45 (neighborhood community plan §5 — the moderation &amp; reports
 * layer): the administrative moderation surface, "بنمط L30" verbatim —
 * the GeoAdminController pattern: the chain's own
 * {@code /api/v1/admin/** -> hasRole("ADMIN")} rule (SecurityConfig)
 * + this class-level {@code @PreAuthorize} + the service-level gate as
 * defense in depth (the house's three-layer authorization; negative 403
 * tests are mandatory — the plan's criterion 4).
 *
 * <p><b>The two endpoints</b> (the plan's own contract): the queue read
 * {@code GET /api/v1/admin/reports?status=} (paged, FIFO drain order)
 * and the resolve command {@code POST /api/v1/admin/reports/{id}/resolve}
 * with the body {@code {action: DISMISS|HIDE_CONTENT, note?}} —
 * {@code HIDE_CONTENT} flips the content, alerts its author
 * ({@code CONTENT_MODERATED}) and closes the report {@code RESOLVED}
 * in one transaction; {@code DISMISS} closes the report and touches
 * nothing else.
 *
 * <p><b>The action type gate</b> (the parseCategory convention): the
 * action arrives as a String and parses BEFORE any service call — an
 * invalid value answers the house 400 with the valid vocabulary listed.
 */
@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class ModerationAdminController {

    private final ContentReportService reportService;
    private final CurrentUserProvider currentUserProvider;

    public ModerationAdminController(ContentReportService reportService,
                                     CurrentUserProvider currentUserProvider) {
        this.reportService = reportService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/reports")
    @Operation(summary = "Read the moderation report queue (admin)",
            description = "The report queue on its complete FIFO drain order (oldest first) — "
                    + "the optional status axis filters OPEN/RESOLVED/DISMISSED; absent = the "
                    + "whole queue. Deterministic pagination on the complete sort key "
                    + "(createdAt ASC, id ASC).")
    public ResponseEntity<PagedResponse<ContentReportView>> queue(
            @Parameter(description = "Optional status filter — OPEN, RESOLVED or DISMISSED")
            @RequestParam(required = false) String status,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(
                reportService.getReports(parseStatus(status), pageable)));
    }

    @PostMapping("/reports/{reportId}/resolve")
    @Operation(summary = "Resolve a report (admin)",
            description = "The ONE transition out of OPEN. DISMISS: the report closes "
                    + "DISMISSED, the content stays exactly as it is. HIDE_CONTENT: the post "
                    + "flips to HIDDEN_BY_MODERATOR (or the comment takes the soft delete, per "
                    + "the target type), the author is alerted (CONTENT_MODERATED) after commit, "
                    + "and the report closes RESOLVED — all in one transaction. A report that "
                    + "already left OPEN answers 409 (closed history stays closed); an unknown "
                    + "report answers 404. The optional note (max 2000 characters) is the "
                    + "administrative free text recorded with the action.")
    public ResponseEntity<ContentReportView> resolve(
            @PathVariable UUID reportId,
            @Valid @RequestBody ResolveReportRequest request,
            Authentication authentication) {
        UUID adminId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(reportService.resolveReport(
                adminId, reportId, parseAction(request.action()), request.note()));
    }

    /**
     * The status gate (the parseCategory convention): null/blank is the
     * absent filter (the whole queue); an invalid value answers the
     * house 400 listing the valid vocabulary.
     */
    private static ReportStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ReportStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid status '" + raw + "' — valid values: OPEN, RESOLVED, DISMISSED");
        }
    }

    /** The action gate — the same String-in/enum-out convention. */
    private static ModerationAction parseAction(String raw) {
        try {
            return ModerationAction.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid action '" + raw + "' — valid values: DISMISS, HIDE_CONTENT");
        }
    }

    /**
     * The resolve body: the plan's own {@code {action: DISMISS|
     * HIDE_CONTENT}} plus the optional administrative note (the house
     * authored-message bound, 2000 — the L42 comment body's own
     * documented limit).
     */
    public record ResolveReportRequest(
            @NotBlank
            @Schema(description = "The moderation outcome — close only, or close and hide.",
                    allowableValues = {"DISMISS", "HIDE_CONTENT"}, example = "HIDE_CONTENT")
            String action,

            @Size(max = 2000)
            @Schema(description = "The administrative note recorded with the resolution "
                    + "(optional, max 2000 characters).", maxLength = 2000)
            String note
    ) {
    }
}
