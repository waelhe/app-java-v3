package com.marketplace.shared.api;

import java.util.Optional;
import java.util.UUID;

public interface UserLookupPort {

    Optional<UserSummary> findById(UUID userId);
}
