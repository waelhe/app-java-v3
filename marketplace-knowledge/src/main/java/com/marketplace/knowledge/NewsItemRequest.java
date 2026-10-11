package com.marketplace.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * D-3 (JT-19/D-30): the publication body — the
 * {@code KnowledgeEntryRequest} house shape (a top-level
 * validation-carrier record the service receives directly). The
 * attribution pair is REQUIRED: {@code sourceUrl} (the original link)
 * and {@code publishedAt} (the original date) ride every read forever —
 * AC-20-09. The publisher must be VERIFIED (the service's 409 otherwise
 * — the delegated-source gate). The geo scope is OPTIONAL
 * ({@code null} = the whole board); when present it must be a level-3
 * neighborhood node (the port's own 404 for an unknown node, the
 * level-3 400 — the knowledge/events discipline).
 */
public record NewsItemRequest(
        @NotNull
        @Schema(description = "The publishing outlet's id — must be a VERIFIED news publisher.")
        UUID publisherId,
        @NotBlank @Size(max = 200)
        @Schema(description = "The item's headline.", example = "افتتاح الطريق الدائري الجديد لحي القضية")
        String title,
        @Size(max = 2000)
        @Schema(description = "The optional summary shown on the board.")
        String summary,
        @NotBlank @Size(max = 1000)
        @Schema(description = "The original article's link — the attribution rides every read (AC-20-09).")
        String sourceUrl,
        @NotNull
        @Schema(description = "The ORIGINAL publication date — required, and immutable on correction.")
        Instant publishedAt,
        @Schema(description = "The optional geo scope — a level-3 neighborhood node; omitted = the whole board.")
        UUID locationId
) {
}
