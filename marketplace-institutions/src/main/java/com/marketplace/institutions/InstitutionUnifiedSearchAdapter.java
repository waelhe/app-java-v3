package com.marketplace.institutions;

import com.marketplace.shared.api.UnifiedSearchHit;
import com.marketplace.shared.api.UnifiedSearchQuery;
import com.marketplace.shared.api.UnifiedSearchSource;
import com.marketplace.shared.api.UnifiedSearchSourcePort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): the institutions registry's contribution. The read
 * rides the repository's own unified method — the SAME visibility contract
 * as the public registry board (every verification state — the honest
 * registry; the soft-delete filter applies), the deterministic (name, id)
 * order, the address riding the snippet (an institution's where-is is
 * part of its answer).
 */
@Component
public class InstitutionUnifiedSearchAdapter implements UnifiedSearchSourcePort {

    private final InstitutionRepository repository;

    public InstitutionUnifiedSearchAdapter(InstitutionRepository repository) {
        this.repository = repository;
    }

    @Override
    public UnifiedSearchSource source() {
        return UnifiedSearchSource.INSTITUTION;
    }

    @Override
    public List<UnifiedSearchHit> search(UnifiedSearchQuery query) {
        return repository.searchPublicTextUnified(
                        query.locationId(), query.text(), PageRequest.of(0, query.limitPerSource()))
                .stream()
                .map(institution -> new UnifiedSearchHit(
                        UnifiedSearchSource.INSTITUTION,
                        institution.getId(),
                        institution.getName(),
                        UnifiedSearchSourcePort.excerpt(
                                institution.getAddress() == null
                                        ? institution.getDescription()
                                        : institution.getAddress() + " — " + institution.getDescription(),
                                KnowledgeUnifiedSearchAdapter.SNIPPET_CODE_POINTS),
                        institution.getLocationId(),
                        "/institutions/" + institution.getId()))
                .toList();
    }
}
