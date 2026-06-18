package com.marketplace.identity.spi;

import com.marketplace.shared.api.UserSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;

import java.util.UUID;

/**
 * SPI for cross-module access to identity/user operations.
 */
@NamedInterface("identity-spi")
public interface IdentitySpi {

    Page<UserSummary> findAllSummaries(Pageable pageable);

    void updateUserRole(UUID userId, String newRole);

    /**
     * Suspends a user account (disables login).
     * @param userId the user to suspend
     */
    void suspendUser(UUID userId);

    /**
     * Reactivates a suspended user account.
     * @param userId the user to reactivate
     */
    void reactivateUser(UUID userId);
}
