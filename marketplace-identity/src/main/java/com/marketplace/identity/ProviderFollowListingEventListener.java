package com.marketplace.identity;

import com.marketplace.shared.api.ListingActivatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * W4 (yelp-level plan §5 — G21): the identity module's consumer of the
 * catalog's activation event — the THIRD declared consumer of the same
 * publisher (search's {@code SavedSearchEventListener} and the community
 * bridge proved the pattern; one publisher, many consumers, the Modulith
 * fan-out the plans document as "ناشر واحد مستهلكان").
 *
 * <p>Same contract as every house listener — after commit, its own
 * transaction (REQUIRES_NEW), the framework's retry: a failure mid-bridge
 * never loses the activation (the registry entry stays incomplete until
 * the whole follow-scan, ledger-insert and publication unit succeeds).
 *
 * <p>The bridge logic itself lives on
 * {@link ProviderFollowService#onListingActivated} — the follow domain's
 * own read — exactly the way the search side's listener delegates to
 * {@code SavedSearchService.processListingActivated} and the community
 * side's to {@code NeighborhoodMembershipService.onListingActivated}.
 */
@Component
public class ProviderFollowListingEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(ProviderFollowListingEventListener.class);

    private final ProviderFollowService providerFollowService;

    public ProviderFollowListingEventListener(ProviderFollowService providerFollowService) {
        this.providerFollowService = providerFollowService;
    }

    @ApplicationModuleListener
    public void onListingActivated(ListingActivatedEvent event) {
        int alerted = providerFollowService.onListingActivated(
                event.listingId(), event.providerId());
        log.info("Provider-follow bridge completed for listing {}: {} follower(s) alerted",
                event.listingId(), alerted);
    }
}
