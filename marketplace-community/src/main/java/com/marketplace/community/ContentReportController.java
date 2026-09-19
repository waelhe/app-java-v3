package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * L45 (neighborhood community plan §5 — the moderation &amp; reports
 * layer): the report intake surface. One endpoint behind the
 * resource-server chain's {@code anyRequest().authenticated()} — the
 * plan's own words: "مصادق — عضوية الحي غير شرط: المار على تغذية حيه
 * عضو أصلًا" (whoever can see the feed is a member already) — no
 * security-config change, the same zero-config line every layer since
 * L20 has ridden.
 *
 * <p><b>The two type gates</b> (the L42 {@code parseCategory}
 * convention): {@code targetType} and {@code reason} arrive as Strings
 * and parse BEFORE any service call — an invalid value answers the house
 * 400 with the valid vocabulary listed, never an enum-binding 500.
 *
 * <p><b>No rate limiter, by the plan's own allocation:</b> the plan
 * names its limiters explicitly when it wants them (L42's
 * "نمطلتان مسماتان", L44's {@code conversationCreate}) and names none
 * here — the V64 partial unique index (one live report per
 * reporter+target) is the surface's own abuse bound. The optional
 * {@code note} bound (2000) is the house authored-message limit.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ContentReportController {

    private final ContentReportService reportService;
    private final CurrentUserProvider currentUserProvider;

    public ContentReportController(ContentReportService reportService,
                                   CurrentUserProvider currentUserProvider) {
        this.reportService = reportService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/reports")
    @Operation(summary = "Report a piece of content",
            description = "Raises a moderation report on one post or comment. Authenticated; "
                    + "neighborhood membership is deliberately NOT required (whoever can see the "
                    + "feed is a member already — the plan's own reasoning). The target must be "
                    + "VISIBLE content — an unknown, hidden or deleted target answers the honest "
                    + "404. Reporting your own content answers 409, and a second live report on "
                    + "the same target answers 409 (one report per reporter per target). The "
                    + "report lands OPEN in the administrative queue.")
    public ResponseEntity<ContentReportView> create(
            @Valid @RequestBody CreateReportRequest request,
            Authentication authentication) {
        UUID reporterId = currentUserProvider.getCurrentUserId(authentication);
        ContentReportView view = reportService.createReport(
                reporterId,
                parseTargetType(request.targetType()),
                request.targetId(),
                parseReason(request.reason()));
        return ResponseEntity.status(201).body(view);
    }

    /**
     * The target-type gate (the parseCategory convention): a String in,
     * the enum out — an invalid value answers the house 400 listing the
     * valid vocabulary, BEFORE any service call.
     */
    private static ReportTargetType parseTargetType(String raw) {
        try {
            return ReportTargetType.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid targetType '" + raw + "' — valid values: POST, COMMENT");
        }
    }

    /** The reason gate — the same String-in/enum-out convention. */
    private static ReportReason parseReason(String raw) {
        try {
            return ReportReason.valueOf(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException(
                    "Invalid reason '" + raw + "' — valid values: "
                            + "SPAM, HARASSMENT, INAPPROPRIATE, OTHER");
        }
    }

    /**
     * The report body: the target's type and id (the plan's extensible
     * pair) and the reason — no reporter free text (the plan's entity
     * carries none: the reason enum plus the reported content are the
     * moderator's complete evidence).
     */
    public record CreateReportRequest(
            @NotBlank
            @Schema(description = "What is being reported — the target surface's own kind.",
                    allowableValues = {"POST", "COMMENT"}, example = "POST")
            String targetType,

            @NotNull
            @Schema(description = "The target's own id — a neighborhood post id for POST, "
                    + "a comment id for COMMENT. Must reference VISIBLE content.",
                    example = "22222222-2222-4222-8222-222222222201")
            UUID targetId,

            @NotBlank
            @Schema(description = "Why the content is being reported.",
                    allowableValues = {"SPAM", "HARASSMENT", "INAPPROPRIATE", "OTHER"},
                    example = "SPAM")
            String reason
    ) {
    }
}
