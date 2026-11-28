package com.marketplace.identity;

import java.util.List;
import java.util.UUID;

/**
 * Raised by {@link AuthoredContentPurgeService#purge} when one or more module
 * adapters failed while the rest completed and stayed committed.
 *
 * <p>Carrying the failed adapter names, it lets the durable erasure listener
 * ({@code AccountIdentityContentPurger}) run the b-4 audit stage on the same
 * delivery and then <em>rethrow</em>, so the event publication registry keeps
 * the unfinished legs open for the {@code EventPublicationResubmission} sweep
 * instead of silently marking the erasure complete. Not swallowing and not
 * failing the completed adapters is the isolation contract.</p>
 */
class AuthoredContentPurgeIncompleteException extends RuntimeException {

    private final UUID userId;
    private final List<String> failedAdapters;

    AuthoredContentPurgeIncompleteException(UUID userId, List<String> failedAdapters) {
        super("authored content purge incomplete: userId=" + userId
                + ", failedAdapters=" + failedAdapters
                + " — the completed adapters stay committed; the publication stays open "
                + "for resubmission");
        this.userId = userId;
        this.failedAdapters = List.copyOf(failedAdapters);
    }

    UUID userId() {
        return userId;
    }

    List<String> failedAdapters() {
        return failedAdapters;
    }
}
