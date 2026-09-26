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

    /**
     * S2/N4/N6 root fix (comprehensive repair plan §10/1.5): the
     * administrative role change as one transaction on BOTH stores of
     * truth — {@code users.role} (the domain record) and the login-side
     * {@code auth_authorities} projection (replaced through the framework
     * {@code UserDetailsManager.updateUser} contract) that every future
     * token's {@code roles} claim is minted from. The account's issued
     * authorizations are removed with the change (a pre-change refresh
     * token would otherwise keep minting old-role access tokens — the L23
     * documented SAS basis), the caches are invalidated through the
     * standing AFTER_COMMIT channel, a {@code UserRoleChanged} domain
     * event is published in-transaction, and the action is recorded in
     * the structured audit line.
     *
     * <p>The L23 last-active-ADMIN counting constraint applies to the
     * downgrade surface verbatim: removing the {@code ROLE_ADMIN}
     * authority from the only enabled account holding it is rejected —
     * the documented invariant is "the last active ADMIN is untouchable",
     * and an operative role change must not open the lockout hole the
     * disable guard already closes.
     *
     * @param userId  the identity projection id (the {@code users} row)
     * @param newRole the stored {@code UserRole} name
     *                ({@code CONSUMER}/{@code PROVIDER}/{@code ADMIN})
     * @param actor   the acting administrator (JWT subject), recorded with
     *                the action in the audit line
     */
    void updateUserRole(UUID userId, String newRole, String actor);

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

    /**
     * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 — the
     * extended purges, the plan's §7 Phase 3 row): the free-text purge —
     * every text the (already-pseudonymized) subject authored across the
     * owning modules, base tables and Envers mirrors, UPDATE-only (the
     * plan's letter: "UPDATE عبر الجداول"); shared records keep their
     * structure and non-text columns (Art. 17(3)(b) / 20(4) — the plan's
     * §4). Idempotent by the port contract: a re-run purges zero rows.
     *
     * @param userId the identity projection id (the {@code users} row)
     * @param reason the administrative reason, recorded with the action
     * @param actor  the acting administrator (JWT subject), recorded with
     *               the action
     * @return the total number of rows whose text was actually purged
     *         (base + Envers mirrors) — zero on a re-run
     * @throws com.marketplace.shared.api.ResourceNotFoundException
     *         no users row for the id
     * @throws com.marketplace.shared.api.ConflictException
     *         the account is not pseudonymized — the purge completes an
     *         erasure flow (pseudonymize first)
     */
    int purgeAuthoredContent(UUID userId, String reason, String actor);

    /**
     * I7 Phase 3 (account-pseudonymization-plan §2 gate b-4 — the audit
     * history purge, the plan's §7 Phase 3 row): purges the
     * (already-pseudonymized) subject's audit identity — the plan's purge
     * option, verbatim: "حذف صفوف {@code users_aud} للمستخدم (WHERE
     * id=…) + كنس {@code created_by}/{@code updated_by} عبر نحو 20
     * جدولاً — عملية ثقيلة تُشغَّل خارج المعاملة، تقبل التدرّج". The
     * subject closure is recovered from the mirror history FIRST (the
     * original raw subject survives only there), the column scrub runs
     * one autocommitted statement per (table, column) across every table
     * the live schema reports carrying the columns, then the mirror rows
     * die wholesale. Idempotent: a re-run answers zero on both counts.
     *
     * @param userId the identity projection id (the {@code users} row)
     * @param reason the administrative reason, recorded with the action
     * @param actor  the acting administrator (JWT subject), recorded with
     *               the action
     * @return the measured outcome — audit cells nulled and mirror
     *         revisions deleted
     * @throws com.marketplace.shared.api.ResourceNotFoundException
     *         no users row for the id
     * @throws com.marketplace.shared.api.ConflictException
     *         the account is not pseudonymized — the purge completes an
     *         erasure flow (pseudonymize first)
     */
    AuditHistoryPurgeResult purgeAuditHistory(UUID userId, String reason, String actor);
}
