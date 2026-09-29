package com.marketplace.community;

import com.marketplace.shared.api.NeighborhoodTrustLookupPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Community-owned adapter for horizontal consumers such as direct messaging. */
@Component
class CommunityTrustLookupAdapter implements NeighborhoodTrustLookupPort {
    private final NeighborhoodMembershipRepository repository;

    CommunityTrustLookupAdapter(NeighborhoodMembershipRepository repository) { this.repository = repository; }

    @Override
    public TrustState trustFor(UUID userId) {
        return repository.findByUserId(userId)
                .map(membership -> TrustState.valueOf(membership.getVerificationState().name()))
                .orElse(TrustState.NO_MEMBERSHIP);
    }
}
