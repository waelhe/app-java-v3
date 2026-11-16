package com.marketplace.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * D-3 (JT-19/D-30): the news item's read model — the
 * {@code from}-factory house shape (the entity never crosses the wire).
 * The HONEST view (AC-20-09/AC-20-10): the attribution rides every read
 * (publisher name + trust mark, the original link, the original date),
 * and the correction marker rides when it exists ({@code corrected} +
 * {@code correctedAt} + {@code correctionNote}) — the correction is
 * DISPLAYED, never the muting. Withdrawn items never reach this record
 * (the board and the detail both stop returning them — the 404).
 *
 * <p>Assembled INSIDE the module by the service (the ProductQaService
 * discipline): the controller never touches the entities (the ArchUnit
 * HTTP-boundary rule — the wire speaks this data-minimized record
 * only).</p>
 *
 * @param publisherVerified the trust mark, not the whole state machine —
 *                          the reader's question is verified-or-not; the
 *                          publisher's own registry state is the admin
 *                          surface's read ({@link NewsPublisherResponse}).
 */
public record NewsItemResponse(
        @Schema(description = "The item's id.") UUID id,
        @Schema(description = "The publishing outlet's id.") UUID publisherId,
        @Schema(description = "The publishing outlet's name — the attribution rides every read (AC-20-09).") String publisherName,
        @Schema(description = "Whether the outlet holds the VERIFIED trust mark.") boolean publisherVerified,
        @Schema(description = "The item's headline.") String title,
        @Schema(description = "The item's summary (null when the item carries none).") String summary,
        @Schema(description = "The original article's link (AC-20-09).") String sourceUrl,
        @Schema(description = "The ORIGINAL publication date — never re-dated by a correction (the honest history).") Instant publishedAt,
        @Schema(description = "The optional geo scope (the level-3 node; null = the whole board).") UUID locationId,
        @Schema(description = "Whether the item was corrected — the correction is displayed, never the muting (AC-20-10).") boolean corrected,
        @Schema(description = "The correction's timestamp (null while uncorrected).") Instant correctedAt,
        @Schema(description = "The correction's REQUIRED note (null while uncorrected).") String correctionNote
) {

    public static NewsItemResponse from(NewsItem item, NewsPublisher publisher) {
        return new NewsItemResponse(
                item.getId(),
                item.getPublisherId(),
                publisher == null ? null : publisher.getName(),
                publisher != null && publisher.getVerificationState() == NewsPublisherState.VERIFIED,
                item.getTitle(),
                item.getSummary(),
                item.getSourceUrl(),
                item.getPublishedAt(),
                item.getLocationId(),
                item.isCorrected(),
                item.getCorrectedAt(),
                item.getCorrectionNote());
    }
}
