package com.marketplace.app.search;

import com.marketplace.community.spi.CommunityPostSearchPort;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.shared.api.UnifiedSearchDomain;
import com.marketplace.shared.api.UnifiedSearchHit;
import com.marketplace.shared.api.UnifiedSearchPort;
import com.marketplace.shared.api.MarketplaceSearchPort;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The unified-search dispatcher (§5.1) — the composition-root
 * implementation of {@link UnifiedSearchPort} (the pom's own words: the app
 * is the assembly point; consuming named SPI ports needs no new module
 * edge). When the connected source count grows past the measured two, the
 * dispatch migrates into the search module behind the SAME contract — no
 * signature change.
 *
 * <p><b>The dispatcher adds nothing:</b> it maps and routes only — it holds
 * ports, not repositories, so no eligibility bypass path exists by
 * construction. The LISTINGS leg reuses the standing
 * {@link MarketplaceSearchPort} orchestration (text + the geo node through
 * its own resolution: an unknown node 404s, never a silently-empty page);
 * the COMMUNITY_POSTS leg reuses the community SPI (membership-scoped
 * visibility, Arabic FTS primary + pg_trgm fallback) and requires an
 * authenticated caller — a missing location NEVER widens a local query into
 * a silent national one.
 */
@Service
public class UnifiedSearchDispatcher implements UnifiedSearchPort {

    private final MarketplaceSearchPort marketplaceSearchPort;
    private final CommunityPostSearchPort communityPostSearchPort;

    public UnifiedSearchDispatcher(MarketplaceSearchPort marketplaceSearchPort,
                                   CommunityPostSearchPort communityPostSearchPort) {
        this.marketplaceSearchPort = marketplaceSearchPort;
        this.communityPostSearchPort = communityPostSearchPort;
    }

    @Override
    public PagedResponse<UnifiedSearchHit> search(UUID callerId, UnifiedSearchDomain domain,
                                                  String query, UUID locationId, PagedRequest request) {
        return switch (domain) {
            case LISTINGS -> searchListings(query, locationId, request);
            case COMMUNITY_POSTS -> searchCommunityPosts(callerId, query, request);
        };
    }

    private PagedResponse<UnifiedSearchHit> searchListings(String query, UUID locationId,
                                                           PagedRequest request) {
        // The common-subset criteria: text + the location node only — the
        // listings facets stay on their own measured surface (no giant
        // criteria object crosses the unified boundary).
        SearchCriteria criteria = new SearchCriteria(
                query, null, null, null, null, null, null,
                locationId, null, null, null, null, null, null, null, null, null);
        return marketplaceSearchPort.search(criteria, request)
                .map(listing -> new UnifiedSearchHit(
                        listing.id(),
                        UnifiedSearchDomain.LISTINGS,
                        "listing",
                        listing.title(),
                        null,
                        null,
                        null,
                        null));
    }

    private PagedResponse<UnifiedSearchHit> searchCommunityPosts(UUID callerId, String query,
                                                                 PagedRequest request) {
        if (callerId == null) {
            throw new BadRequestException(
                    "Community search requires an authenticated caller (the membership scope)");
        }
        return communityPostSearchPort.search(callerId, query, request);
    }
}
