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
     * L23 (feature-expansion roadmap §5): administrative account disable/enable.
     * Flips {@code auth_users.enabled} through the framework-managed
     * {@code UserDetailsManager}, kills the account's issued authorizations
     * (refresh tokens die with them) when disabling, and records the action
     * with its reason in the audit log (V8 {@code audit_log}).
     *
     * @param userId the identity projection id (the {@code users} row)
     * @param status {@code DISABLED} or {@code ENABLED}
     * @param reason the administrative reason, recorded with the action
     * @param actor  the acting administrator (JWT subject), recorded as
     *               {@code changed_by}
     */
    void updateUserStatus(UUID userId, String status, String reason, String actor);
}
