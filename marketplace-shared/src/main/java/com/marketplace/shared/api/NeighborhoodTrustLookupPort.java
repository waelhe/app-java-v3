package com.marketplace.shared.api;

import java.util.UUID;

/** Cross-module read seam for the community trust policy; no consumer reaches community tables. */
public interface NeighborhoodTrustLookupPort {
    TrustState trustFor(UUID userId);

    enum TrustState { NO_MEMBERSHIP, UNVERIFIED, PENDING, VERIFIED, REJECTED }
}
