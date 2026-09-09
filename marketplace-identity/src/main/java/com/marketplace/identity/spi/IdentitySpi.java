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

    /**
     * I7 Phase 1 (account-pseudonymization-plan §5-أ, the plan adopted by
     * PR #276): account pseudonymization — erases the login identity and
     * replaces the direct identifiers in one transaction, then lets the
     * existing channels finish (cache eviction AFTER_COMMIT, access tokens
     * by their 900s TTL). The UUID and every referencing row stay (Art.
     * 17(3)(b) / 20(4) — records persist in pseudonymized form); the audit
     * columns and free texts are the declared residuals of the plan's gates
     * b-4/b-3.
     *
     * @param userId the identity projection id (the {@code users} row)
     * @param reason the administrative reason, recorded with the action
     * @param actor  the acting administrator (JWT subject), recorded with
     *               the action
     * @throws com.marketplace.shared.api.ServiceUnavailableException
     *         the HMAC secret channel is unbound (503 SU-001 — the
     *         capability is OFF, not broken)
     * @throws com.marketplace.shared.api.ConflictException
     *         the target is the last active ADMIN account
     */
    void pseudonymizeAccount(UUID userId, String reason, String actor);
}
