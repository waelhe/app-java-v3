package com.marketplace.community;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * B-19 (compliance plan C.11): the rule's read model — the operator's
 * board row, carrying the condition (the pair + the threshold), the
 * lifecycle state, and the auditing fields (who last touched the rule
 * and when — the change history's own source).
 */
public record ModerationRuleView(
        @Schema(description = "The rule's id.", example = "3f2c8a64-...")
        UUID id,
        @Schema(description = "The target type the rule watches — POST, COMMENT or REVIEW.",
                allowableValues = {"POST", "COMMENT", "REVIEW"}, example = "POST")
        String targetType,
        @Schema(description = "The report reason the rule watches — SPAM, HARASSMENT, INAPPROPRIATE or OTHER.",
                allowableValues = {"SPAM", "HARASSMENT", "INAPPROPRIATE", "OTHER"}, example = "SPAM")
        String reason,
        @Schema(description = "How many distinct live OPEN reporters on the same target fire the rule.",
                example = "3")
        int threshold,
        @Schema(description = "The pause — a disabled rule is skipped by the evaluation lookup.", example = "true")
        boolean enabled,
        @Schema(description = "Who last changed the rule (the auditing field).")
        String updatedBy,
        @Schema(description = "When the rule was last changed (the auditing field).")
        java.time.Instant updatedAt
) {

    static ModerationRuleView of(ModerationRule rule) {
        return new ModerationRuleView(rule.getId(), rule.getTargetType().name(),
                rule.getReason().name(), rule.getThreshold(), rule.isEnabled(),
                rule.getUpdatedBy(), rule.getUpdatedAt());
    }
}
