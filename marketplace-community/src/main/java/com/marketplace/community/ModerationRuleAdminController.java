package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * B-19 (compliance plan C.11 — محرك قواعد إشراف تلقائية): the rules'
 * administrative surface — the {@code ModerationAdminController} house
 * shape verbatim ("بنمط L30"): the chain's own
 * {@code /api/v1/admin/** -> hasRole("ADMIN")} rule (SecurityConfig) +
 * this class-level {@code @PreAuthorize} + the service-level gate as
 * defense in depth (the house's three-layer authorization; the negative
 * 403 lives on the standing admin-chain tests the whole
 * {@code /api/v1/admin/**} surface rides).
 *
 * <p><b>The four endpoints (the CRUD the plan mandates):</b> the board
 * {@code GET /api/v1/admin/moderation-rules}, the registration
 * {@code POST} (201; a duplicate live pair answers 409), the operator's
 * revision {@code PATCH /{id}/threshold} + the pause/resume
 * {@code PATCH /{id}/enabled} (unknown rule 404), and the retirement
 * {@code DELETE /{id}} (204 — the soft delete: the row stays for the
 * audit trail, the slot releases).
 *
 * <p><b>The type gates (the parseStatus/parseAction convention):</b>
 * the target type and the reason arrive as Strings and parse BEFORE
 * any service call — an invalid value answers the house 400 with the
 * valid vocabulary listed. The rows' own auditing fields (who
 * registered, revised, retired — and when) fill through the
 * {@code AuditingEntityListener} from the security context: the
 * change history needs no explicit id plumbing.
 */
@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class ModerationRuleAdminController {

    private final ModerationRuleService ruleService;

    public ModerationRuleAdminController(ModerationRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping("/moderation-rules")
    @Operation(summary = "The automatic moderation rules board (admin)",
            description = "Every live rule in the stable (target type, reason) order — the data "
                    + "the automatic engine reads at request time inside the report-creation "
                    + "transaction. The values are data: never migrations.")
    public ResponseEntity<List<ModerationRuleView>> rules() {
        return ResponseEntity.ok(ruleService.rules().stream().map(ModerationRuleView::of).toList());
    }

    @PostMapping("/moderation-rules")
    @Operation(summary = "Register an automatic moderation rule (admin)",
            description = "One rule over the report machine's own axes: the target type, the "
                    + "reason, and the distinct-reporter threshold that fires the automatic "
                    + "HIDE_CONTENT (hide the content, alert the author, resolve every OPEN "
                    + "report on the target, publish the reporters' adjudication facts — all "
                    + "inside the report-creation transaction). Born enabled; the pause is the "
                    + "separate enabled verb. A duplicate live pair answers 409.")
    public ResponseEntity<ModerationRuleView> register(
            @Valid @RequestBody RuleRegistrationRequest request) {
        ReportTargetType targetType = parseTargetType(request.targetType());
        ReportReason reason = parseReason(request.reason());
        ModerationRule saved = ruleService.registerRule(targetType, reason, request.threshold());
        return ResponseEntity.status(HttpStatus.CREATED).body(ModerationRuleView.of(saved));
    }

    @PatchMapping("/moderation-rules/{ruleId}/threshold")
    @Operation(summary = "Revise a rule's threshold (admin)",
            description = "The rule's whole value — how many distinct live OPEN reporters fire "
                    + "the automatic action. The auditing fields record who and when; an "
                    + "unknown rule answers 404.")
    public ResponseEntity<ModerationRuleView> reviseThreshold(
            @PathVariable UUID ruleId,
            @Valid @RequestBody ThresholdRevisionRequest request) {
        return ResponseEntity.ok(ModerationRuleView.of(
                ruleService.setThreshold(ruleId, request.threshold())));
    }

    @PatchMapping("/moderation-rules/{ruleId}/enabled")
    @Operation(summary = "Pause or resume a rule (admin)",
            description = "The pause without losing the row: a disabled rule is skipped by the "
                    + "evaluation lookup (the request-time read) while the audit trail keeps "
                    + "its whole history. An unknown rule answers 404.")
    public ResponseEntity<ModerationRuleView> setEnabled(
            @PathVariable UUID ruleId,
            @Valid @RequestBody EnabledFlipRequest request) {
        return ResponseEntity.ok(ModerationRuleView.of(
                ruleService.setEnabled(ruleId, request.enabled())));
    }

    @DeleteMapping("/moderation-rules/{ruleId}")
    @Operation(summary = "Retire a rule (admin)",
            description = "The house soft delete: the row stays for the audit trail (the Envers "
                    + "DEL revision + the auditing fields) and the (target type, reason) slot "
                    + "releases for a fresh registration. An unknown rule answers 404.")
    public ResponseEntity<Void> retire(@PathVariable UUID ruleId) {
        ruleService.retireRule(ruleId);
        return ResponseEntity.noContent().build();
    }

    /** The target-type gate — the parseAction convention, the machine's own vocabulary. */
    private static ReportTargetType parseTargetType(String raw) {
        try {
            return ReportTargetType.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid target type '" + raw + "' — valid values: POST, COMMENT, REVIEW");
        }
    }

    /** The reason gate — the same String-in/enum-out convention. */
    private static ReportReason parseReason(String raw) {
        try {
            return ReportReason.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid reason '" + raw + "' — valid values: SPAM, HARASSMENT, INAPPROPRIATE, OTHER");
        }
    }

    /** The rule registration body — the report machine's own axes. */
    public record RuleRegistrationRequest(
            @NotBlank
            @Schema(description = "The target type the rule watches.",
                    allowableValues = {"POST", "COMMENT", "REVIEW"}, example = "POST")
            String targetType,
            @NotBlank
            @Schema(description = "The report reason the rule watches.",
                    allowableValues = {"SPAM", "HARASSMENT", "INAPPROPRIATE", "OTHER"}, example = "SPAM")
            String reason,
            @NotNull @Min(1) @Max(1_000_000)
            @Schema(description = "How many distinct live OPEN reporters on the same target "
                    + "fire the automatic action (at least 1 — a rule that fires on zero "
                    + "reports is not a rule).", example = "3")
            int threshold
    ) {
    }

    /** The threshold revision body. */
    public record ThresholdRevisionRequest(
            @NotNull @Min(1) @Max(1_000_000)
            @Schema(description = "The revised distinct-reporter threshold.", example = "5")
            int threshold
    ) {
    }

    /** The pause/resume body — the console flag flip's own shape. */
    public record EnabledFlipRequest(
            @NotNull
            @Schema(description = "The rule's lifecycle state — false pauses the evaluation "
                    + "lookup without losing the row.", example = "false")
            boolean enabled
    ) {
    }
}
