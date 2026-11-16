package com.marketplace.community;

import com.marketplace.shared.api.ListingActivatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * L46 (neighborhood community plan §5 — the community realestate bridge):
 * the community module's consumer of the catalog's activation event — the
 * second declared consumer of the same publisher (search's
 * {@code SavedSearchEventListener} proved the pattern in L35; one
 * publisher, many consumers, the Modulith fan-out the plan documents as
 * "ناشر واحد مستهلكان"). Same contract as every house listener — after
 * commit, its own transaction (REQUIRES_NEW), the framework's retry: a
 * failure mid-bridge never loses the activation (the registry entry stays
 * incomplete until the whole member-resolution and publication unit
 * succeeds).
 *
 * <p>The bridge logic itself lives on
 * {@link NeighborhoodMembershipService#onListingActivated} — the
 * membership domain's own read — exactly the way the search side's
 * listener delegates to {@code SavedSearchService.processListingActivated}.
 */
@Component
public class NeighborhoodListingEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(NeighborhoodListingEventListener.class);

    private final NeighborhoodMembershipService membershipService;

    public NeighborhoodListingEventListener(NeighborhoodMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @ApplicationModuleListener
    public void onListingActivated(ListingActivatedEvent event) {
        int alerted = membershipService.onListingActivated(
                event.listingId(), event.providerId());
        log.info("Neighborhood bridge completed for listing {}: {} member(s) alerted",
                event.listingId(), alerted);
    }
}
