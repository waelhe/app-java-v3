package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): one source's search brief. The text is the user's
 * own query (never an inferred paraphrase); {@code locationId} scopes to
 * one geo node when the caller supplies one explicitly (null = all
 * locations); {@code limitPerSource} caps each source's contribution so no
 * single domain floods the merged answer.
 */
public record UnifiedSearchQuery(
        String text,
        UUID locationId,
        int limitPerSource
) {

    public UnifiedSearchQuery {
        if (text == null || text.isBlank()) {
            throw new BadRequestException("unified search requires a text query");
        }
        if (limitPerSource < 1 || limitPerSource > 20) {
            throw new BadRequestException("limitPerSource must be within [1, 20]");
        }
    }
}
