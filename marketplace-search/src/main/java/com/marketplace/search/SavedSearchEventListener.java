package com.marketplace.search;

import com.marketplace.shared.api.ListingActivatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): the
 * search module's consumer of the catalog's activation event. Same
 * contract as the notifications module's listeners — after commit, its
 * own transaction (REQUIRES_NEW), the framework's retry: a failure
 * mid-scan never loses the activation (the registry entry stays
 * incomplete until the whole scan + publication unit succeeds).
 */
@Component
public class SavedSearchEventListener {

    private static final Logger log = LoggerFactory.getLogger(SavedSearchEventListener.class);

    private final SavedSearchService savedSearchService;

    public SavedSearchEventListener(SavedSearchService savedSearchService) {
        this.savedSearchService = savedSearchService;
    }

    @ApplicationModuleListener
    public void onListingActivated(ListingActivatedEvent event) {
        savedSearchService.processListingActivated(event.listingId(), event.providerId());
        log.info("Saved-search scan completed for listing: {}", event.listingId());
    }
}
