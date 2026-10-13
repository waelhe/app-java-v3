package com.marketplace.search;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.MarketplaceSearchPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Stage 10 (plan D-13, ADR-0006): the deterministic baseline's eval
 * runner — the plan's own gate shape («المرشح يتفوق على خط الأساس الحتمي
 * باختبار معتمد»): the labeled cases score the EXISTING deterministic
 * ranking (the search pipeline's canonical ordering through the shared
 * port — the same behavior REST and AI callers see), and the metrics
 * (NDCG@10, MRR) are the evidence any future learned model must beat on
 * the SAME set. No model exists here: level 0 of the plan's AI ladder,
 * code only.
 */
@Service
public class OrderingEvalService {

    private final OrderingEvalCaseRepository caseRepository;
    private final MarketplaceSearchPort searchPort;

    public OrderingEvalService(OrderingEvalCaseRepository caseRepository,
                               MarketplaceSearchPort searchPort) {
        this.caseRepository = caseRepository;
        this.searchPort = searchPort;
    }

    /**
     * Scores the deterministic ranking for one labeled query.
     *
     * @param query the labeled query
     * @return the metrics (NDCG@10, MRR) and the ranked listing ids the
     *         run observed — the report is the baseline's own evidence
     */
    @Transactional(readOnly = true)
    public EvalReport evaluate(String query) {
        List<OrderingEvalCase> cases = caseRepository.findByQuery(query);
        if (cases.isEmpty()) {
            return new EvalReport(query, 0, 0.0, 0.0, List.of());
        }
        SearchCriteria criteria = new SearchCriteria(query, null, null, null);
        PagedResponse<ListingSummary> page = searchPort.search(criteria, new PagedRequest(0, 50, List.of()));
        List<UUID> ranked = page.content().stream()
                .map(ListingSummary::id)
                .toList();
        double ndcg = ndcgAt10(cases, ranked);
        double mrr = mrr(cases, ranked);
        return new EvalReport(query, cases.size(), ndcg, mrr, ranked);
    }

    /**
     * NDCG@10 over the graded relevance — the official information-retrieval
     * formula (the logarithmic discount), computed over the deterministic
     * ranking's own order.
     */
    static double ndcgAt10(List<OrderingEvalCase> cases, List<UUID> ranked) {
        java.util.Map<UUID, Integer> relevance = new java.util.HashMap<>();
        cases.forEach(c -> relevance.put(c.getListingId(), c.getRelevance()));
        double dcg = 0;
        for (int i = 0; i < Math.min(10, ranked.size()); i++) {
            Integer rel = relevance.get(ranked.get(i));
            if (rel != null && rel > 0) {
                dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
            }
        }
        List<Integer> ideal = relevance.values().stream()
                .filter(r -> r > 0)
                .sorted(java.util.Comparator.reverseOrder())
                .limit(10)
                .toList();
        double idcg = 0;
        for (int i = 0; i < ideal.size(); i++) {
            idcg += (Math.pow(2, ideal.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    /** MRR over the first relevant hit (relevance ≥ 2 — the useful threshold). */
    static double mrr(List<OrderingEvalCase> cases, List<UUID> ranked) {
        java.util.Set<UUID> relevant = new java.util.HashSet<>();
        cases.forEach(c -> {
            if (c.getRelevance() >= 2) {
                relevant.add(c.getListingId());
            }
        });
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * The report carrier — the baseline's own evidence (the plan's
     * documented signals: the ranking's reasons ride the search
     * pipeline's own deterministic formula; the learned gate compares
     * against THESE numbers, never against a claim).
     */
    public record EvalReport(String query, int caseCount, double ndcgAt10, double mrr,
                             List<UUID> rankedListingIds) {
    }
}
