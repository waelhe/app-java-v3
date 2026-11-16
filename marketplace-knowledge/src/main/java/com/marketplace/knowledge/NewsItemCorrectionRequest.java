package com.marketplace.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * D-3 (JT-19/D-30): the correction body — the revision rides the
 * knowledge revise shape (the complete new content re-submits) with the
 * honesty contract this surface adds: {@code correctionNote} is REQUIRED
 * (a correction without its note is a silent edit — the one thing this
 * surface must never do; the record's constraint and the service's own
 * gate carry the same rule, the D-N7 two-sided discipline). The
 * publication date is NOT carried — immutable on correction (the honest
 * history); the geo scope IS carried and must agree with the item's own
 * (the routing-mismatch 400 — the knowledge revise discipline; the scope
 * never silently re-targets).
 */
public record NewsItemCorrectionRequest(
        @NotBlank @Size(max = 200)
        @Schema(description = "The corrected headline — the complete new content re-submits.")
        String title,
        @Size(max = 2000)
        @Schema(description = "The corrected summary (full overwrite — omit to clear it).")
        String summary,
        @NotBlank @Size(max = 1000)
        @Schema(description = "The corrected original link (the attribution pair's moving half).")
        String sourceUrl,
        @Schema(description = "The item's geo scope — must agree with the item's own (immutable on correction).")
        UUID locationId,
        @NotBlank @Size(max = 1000)
        @Schema(description = "The REQUIRED correction note — what changed and why (AC-20-10).", example = "صُحّح رقم المساحة المذكور في الخبر")
        String correctionNote
) {
}
