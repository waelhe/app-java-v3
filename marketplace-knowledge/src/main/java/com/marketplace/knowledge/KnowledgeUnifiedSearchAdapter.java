package com.marketplace.knowledge;

import com.marketplace.shared.api.UnifiedSearchHit;
import com.marketplace.shared.api.UnifiedSearchQuery;
import com.marketplace.shared.api.UnifiedSearchSource;
import com.marketplace.shared.api.UnifiedSearchSourcePort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): the knowledge entries' contribution. The read
 * rides the repository's own {@code searchFullText} — the SAME the
 * knowledge surface speaks (the 'simple' tokenizer over coalesced
 * title+body, the V156 GIN index, the ts_rank relevance with the id
 * tiebreak, and the native path's own {@code is_deleted = false} — a
 * withdrawn entry never answers).
 */
@Component
public class KnowledgeUnifiedSearchAdapter implements UnifiedSearchSourcePort {

    static final int SNIPPET_CODE_POINTS = 160;

    private final KnowledgeEntryRepository repository;

    public KnowledgeUnifiedSearchAdapter(KnowledgeEntryRepository repository) {
        this.repository = repository;
    }

    @Override
    public UnifiedSearchSource source() {
        return UnifiedSearchSource.KNOWLEDGE;
    }

    @Override
    public List<UnifiedSearchHit> search(UnifiedSearchQuery query) {
        return repository.searchFullText(
                        query.text(), null, PageRequest.of(0, query.limitPerSource()))
                .stream()
                .map(entry -> new UnifiedSearchHit(
                        UnifiedSearchSource.KNOWLEDGE,
                        entry.getId(),
                        entry.getTitle(),
                        UnifiedSearchSourcePort.excerpt(entry.getBody(), SNIPPET_CODE_POINTS),
                        entry.getLocationId(),
                        "/knowledge/" + entry.getId()))
                .toList();
    }
}
