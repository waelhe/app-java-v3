package com.marketplace.shared.api;

import java.util.List;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): the merged answer. Deterministic: hits arrive
 * grouped by source in the {@link UnifiedSearchSource} declaration order,
 * each group ranked by its own source (relevance first, then the source's
 * stable tiebreak). {@code consultedSources} is every port that answered;
 * {@code degradedSources} names the sources whose port failed (a failing
 * source degrades to its absence — the response never fails whole because
 * one domain is down).
 */
public record UnifiedSearchResponse(
        String query,
        List<UnifiedSearchHit> hits,
        List<UnifiedSearchSource> consultedSources,
        List<UnifiedSearchSource> degradedSources
) {
}
