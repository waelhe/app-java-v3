package com.marketplace.search;

import com.marketplace.search.spi.CommunityDiscoverySearchAdapter;
import com.marketplace.shared.api.DiscoveryCardView;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.MarketplaceSearchPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Phase 5 (the plan §5.1 — «مدخل موحد للمستخدم بحسب المجال والجغرافيا»):
 * the unified search's composition service — the ONE entry that rides the
 * existing canonical orchestration and adds the newly qualified sources
 * BESIDE it, never instead of it.
 *
 * <p><b>The listings leg is the existing contract, untouched:</b> it goes
 * through {@link MarketplaceSearchPort} (the #524 orchestration —
 * facet dispatch, availability, geographic qualification, sort
 * validation, deterministic ordering and cache semantics). This service
 * re-implements none of it and cannot drift from the REST/AI behavior
 * every existing consumer already gets — the plan's «لا تكرر المسار ولا
 * تعيد بناءه» applied literally.</p>
 *
 * <p><b>The community leg is the phase-5 addition:</b>
 * {@link CommunityDiscoverySearchAdapter} bridges the community domain's
 * public projection port with the hydration-time eligibility re-check,
 * the exact-scope law (an absent location is an honest empty leg, never a
 * national widening) and the observable safe failure (§9.4). The answer
 * carries the leg's availability flag so a dark source can never be
 * mistaken for an empty neighborhood.</p>
 *
 * <p><b>Per-domain answers, not one flattened stream (§5.1: «من دون كائن
 * معايير عملاق بحقول بلا معنى لكل مجال»):</b> the listings keep their
 * {@link ListingSummary} page semantics (totals, ordering, cache) and the
 * community cards speak the {@link DiscoveryCardView} projection (source
 * identity + state + version moment) — mixing them into one page would
 * fabricate comparable totals across domains that do not share one
 * ordering contract. The response shapes stay honest per source.</p>
 */
@Service
public class UnifiedSearchService {

    private final MarketplaceSearchPort listingsLeg;
    private final CommunityDiscoverySearchAdapter communityLeg;

    public UnifiedSearchService(MarketplaceSearchPort listingsLeg,
                                CommunityDiscoverySearchAdapter communityLeg) {
        this.listingsLeg = Objects.requireNonNull(listingsLeg, "listingsLeg must not be null");
        this.communityLeg = Objects.requireNonNull(communityLeg, "communityLeg must not be null");
    }

    /**
     * The unified answer: the canonical listings page (the same dispatch
     * the REST surface and the AI tool ride) beside the community leg's
     * exactly-scoped, re-checked cards.
     */
    public UnifiedSearchAnswer search(SearchCriteria criteria, PagedRequest request) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        Objects.requireNonNull(request, "request must not be null");
        PagedResponse<ListingSummary> listings = listingsLeg.search(criteria, request);
        CommunityDiscoverySearchAdapter.CommunityLegAnswer community =
                communityLeg.search(criteria, request);
        return new UnifiedSearchAnswer(listings, community.cards(), community.sourceAvailable());
    }

    /**
     * The unified search answer — per-domain segments with the source
     * availability made explicit ({@code communitySourceAvailable=false}
     * means the community leg is disabled or failed: §9.4's degraded leg,
     * honestly labelled, never fabricated results).
     */
    public record UnifiedSearchAnswer(
            PagedResponse<ListingSummary> listings,
            List<DiscoveryCardView> communityCards,
            boolean communitySourceAvailable) {

        public UnifiedSearchAnswer {
            communityCards = communityCards == null ? List.of() : List.copyOf(communityCards);
        }
    }
}
