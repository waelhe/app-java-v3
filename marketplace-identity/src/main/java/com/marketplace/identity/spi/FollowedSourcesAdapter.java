package com.marketplace.identity.spi;

import com.marketplace.identity.FollowService;
import com.marketplace.shared.api.FollowedSourcesPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * JT-20 (the discovery waves — AC-20-05): the identity module's
 * implementation of the {@link FollowedSourcesPort} cross-module
 * contract — the FOLLOWED_SOURCES rail's relationship leg.
 *
 * <p>House pattern (the {@code PostLookupAdapter} seam verbatim): the
 * interface lives in shared-api, the data owner implements it, the
 * consumer (the discovery projection) injects it — no module
 * dependency in either direction.
 *
 * <p><b>The honest union (the port's one-home rule):</b> the body lives
 * in {@link FollowService#followedSources(UUID)} — the two follow homes
 * ({@code follows} for USER/GROUP, {@code provider_follows} for
 * PROVIDER) need the entity package's own access, and the house rule is
 * the service composes while the adapters speak the DTOs. This adapter
 * is the seam itself: the discovery rail reads ONE set and never learns
 * two tables exist.
 */
@Component
@Transactional(readOnly = true)
public class FollowedSourcesAdapter implements FollowedSourcesPort {

    private final FollowService followService;

    public FollowedSourcesAdapter(FollowService followService) {
        this.followService = followService;
    }

    @Override
    public Set<FollowedSource> followedSources(UUID userId) {
        return followService.followedSources(userId);
    }
}
