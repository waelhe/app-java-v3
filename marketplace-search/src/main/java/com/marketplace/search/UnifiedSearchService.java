package com.marketplace.search;

import com.marketplace.shared.api.UnifiedSearchHit;
import com.marketplace.shared.api.UnifiedSearchQuery;
import com.marketplace.shared.api.UnifiedSearchResponse;
import com.marketplace.shared.api.UnifiedSearchSource;
import com.marketplace.shared.api.UnifiedSearchSourcePort;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): the orchestrator. It coordinates and owns nothing
 * — the plan's own rule («تنسّق ولا تملك»): every domain answers through
 * its own {@link UnifiedSearchSourcePort} adapter with its own visibility
 * predicates and its own relevance order; this service merges, caps, tags,
 * and measures.
 *
 * <p><b>Deterministic merge:</b> sources answer in the
 * {@link UnifiedSearchSource} declaration order (sorted by name — a stable
 * order that does not depend on bean discovery), each group ranked by its
 * own source with its own tiebreak. No cross-source re-ranking here — the
 * plan's learned ordering (Stage 10) is explicitly NOT this component.
 *
 * <p><b>Safe degradation:</b> a throwing port degrades its source to
 * absence — logged, counted, reported in
 * {@code UnifiedSearchResponse.degradedSources} — and the answer stays
 * whole. The plan's «فشل آمن» gate, verbatim.
 *
 * <p><b>Measured:</b> every consultation lands on the meter registry —
 * {@code search.unified.hits{source}} per consultation and the
 * {@code search.unified.consulted} timer — the plan's «قياس» requirement;
 * the OpenSearch-vs-PG decision (D-06) reads these numbers, not opinions.
 */
@Service
public class UnifiedSearchService {

    static final String HITS_COUNTER = "search.unified.hits";
    static final String CONSULTED_TIMER = "search.unified.consulted";

    private static final Logger log = LoggerFactory.getLogger(UnifiedSearchService.class);

    private final List<UnifiedSearchSourcePort> sources;
    private final MeterRegistry meterRegistry;

    public UnifiedSearchService(List<UnifiedSearchSourcePort> sources, MeterRegistry meterRegistry) {
        this.sources = sources.stream()
                .sorted(Comparator.comparing(s -> s.source().name()))
                .toList();
        this.meterRegistry = meterRegistry;
    }

    public UnifiedSearchResponse search(UnifiedSearchQuery query) {
        List<UnifiedSearchHit> hits = new ArrayList<>();
        List<UnifiedSearchSource> consulted = new ArrayList<>();
        List<UnifiedSearchSource> degraded = new ArrayList<>();
        for (UnifiedSearchSourcePort source : sources) {
            consulted.add(source.source());
            try {
                List<UnifiedSearchHit> sourceHits = meterRegistry.timer(CONSULTED_TIMER,
                        "source", source.source().name()).record(() -> source.search(query));
                hits.addAll(sourceHits);
                meterRegistry.counter(HITS_COUNTER, "source", source.source().name())
                        .increment(sourceHits.size());
            } catch (RuntimeException ex) {
                // Safe degradation: one domain down never fails the whole
                // answer — the plan's gate. The failure is loud (log +
                // metric) and honest (degradedSources), never silent.
                degraded.add(source.source());
                meterRegistry.counter("search.unified.degraded", "source", source.source().name())
                        .increment();
                log.warn("Unified-search source degraded: source={}, queryLength={}",
                        source.source(), query.text().length(), ex);
            }
        }
        return new UnifiedSearchResponse(query.text(), List.copyOf(hits),
                List.copyOf(consulted), List.copyOf(degraded));
    }
}
