package com.marketplace.identity.spi;

import com.marketplace.shared.api.UserSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.Set;
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
     * Phase 1 (the unified plan §10, D-03) — the multi-role GRANT on the
     * assignment model ({@code RoleAssignmentService.grant}): one ACTIVE
     * {@code user_role_assignments} row for the (account, role) pair —
     * the additive layer the V188 effective-authorities view unions into
     * the login-side read, so an active assignment IS an authority at
     * the next token mint. {@code users.role} and its S2/N4/N6
     * {@code auth_authorities} projection stay untouched (the Phase-1
     * letter); the revoke's refresh/session kills are the revoke leg's
     * own.
     *
     * <p>The vocabulary stays closed: {@code CONSUMER}/{@code PROVIDER}/
     * {@code ADMIN} — the V187 CHECK's own list, pinned at the SPI
     * boundary through the {@code UserRole} enum.
     *
     * @param userId the identity projection id (the {@code users} row)
     * @param role   the stored {@code UserRole} name to grant
     * @param actor  the acting administrator (JWT subject), recorded with
     *               the action in the audit line
     * @throws com.marketplace.shared.api.ResourceNotFoundException
     *         no users row for the id
     * @throws com.marketplace.shared.api.ConflictException
     *         the pair already holds an ACTIVE assignment (the clean 409)
     */
    void grantUserRole(UUID userId, String role, String actor);

    /**
     * Phase 1 (the unified plan §10, D-03) — the multi-role REVOKE on the
     * assignment model ({@code RoleAssignmentService.revoke}): stamps the
     * pair's ACTIVE row revoked, removes the account's issued
     * {@code oauth2_authorization} rows in the same transaction (the L23
     * refresh-resurrection kill), and publishes
     * {@code UserRoleAssignmentRevoked} in-transaction — consumed at
     * AFTER_COMMIT to expire the live sessions whose cached authorities
     * still hold the role.
     *
     * <p>The account's PRIMARY role ({@code users.role}) refuses the
     * revoke with the honest 409 — the primary's change path is
     * {@link #updateUserRole} (the two-store command); only
     * granted-beyond-primary assignments ride this surface.
     *
     * @param userId the identity projection id (the {@code users} row)
     * @param role   the stored {@code UserRole} name to revoke
     * @param actor  the acting administrator (JWT subject), recorded with
     *               the action in the audit line
     * @throws com.marketplace.shared.api.ResourceNotFoundException
     *         no users row for the id
     * @throws com.marketplace.shared.api.ConflictException
     *         the role is the account's primary role, or the pair holds
     *         no ACTIVE assignment to revoke
     */
    void revokeUserRole(UUID userId, String role, String actor);

    /**
     * Phase 1 (the unified plan §10, D-03) — the administrative read of
     * the account's ACTIVE role assignments (the multi-role truth the
     * V188 view unions into the login-side authorities), whose
     * primary-role projection the roster's single-value {@code role}
     * field renders.
     *
     * @param userId the identity projection id (the {@code users} row)
     * @return the active assignment role names, sorted (stable admin
     *         display order)
     */
    Set<String> getUserRoles(UUID userId);

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

    /**
     * P1 (the canonical Saleor plan §5-P1 — the Saleor {@code me} root):
     * the caller's own account projection, resolved by the authentication's
     * subject. Read-only: the REST {@code /me} bootstrap (profile sync from
     * the token's freshest claims) keeps its own path — this read answers
     * the stored projection, and a subject with no {@code users} row yet
     * answers empty (the honest absence the nullable {@code me} carries,
     * never a fabricated profile).
     *
     * @param authentication the caller's resource-server authentication
     *                       (the JWT subject is the join key)
     * @return the caller's account projection, empty when no row exists
     */
    java.util.Optional<UserAccountView> findCurrentUserAccount(Authentication authentication);

    /**
     * P1 (the Saleor {@code user} root): the account projection by the
     * native UUID behind the typed global ID. Saleor requires
     * MANAGE_STAFF/MANAGE_USERS/MANAGE_ORDERS — enforced here as the
     * platform's server-side ADMIN capability at the owning use-case
     * boundary (the RBAC mapping row of the divergence registry).
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the caller, whose ADMIN authority is verified
     * @return the account projection, empty when no row exists
     */
    java.util.Optional<UserAccountView> findUserAccountById(UUID id, Authentication authentication);

    /**
     * P1 (the Saleor {@code user} root's {@code email} argument): the
     * account projection by email address. ADMIN-enforced as above.
     *
     * @param email          the account email
     * @param authentication the caller, whose ADMIN authority is verified
     * @return the account projection, empty when no row exists
     */
    java.util.Optional<UserAccountView> findUserAccountByEmail(String email, Authentication authentication);

    /**
     * P1 (the Saleor {@code user} root's {@code externalReference}
     * argument): the account projection by the external identifier of
     * record — the platform's OIDC {@code subject} column (the same join
     * key the JWT {@code sub} uses). ADMIN-enforced as above.
     *
     * @param subject        the external identifier (the OIDC subject)
     * @param authentication the caller, whose ADMIN authority is verified
     * @return the account projection, empty when no row exists
     */
    java.util.Optional<UserAccountView> findUserAccountBySubject(String subject, Authentication authentication);

    /**
     * P1 (the Saleor {@code staffUsers} root): one bounded page of the
     * staff/user roster as a forward-only keyset scroll in the requested
     * sort (an owning column + the {@code id} tiebreaker — the
     * storeProducts/services keyset precedent). ADMIN-enforced as above;
     * the returning page carries the collection total Saleor's
     * {@code totalCount} field expects.
     *
     * @param authentication the caller, whose ADMIN authority is verified
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap)
     * @return the page of account projections plus the collection total
     */
    UserAccountPage scrollStaffUsers(Authentication authentication,
                                     UserAccountSort sort,
                                     org.springframework.data.domain.KeysetScrollPosition position,
                                     org.springframework.data.domain.Limit limit);

    /**
     * P1 (the Saleor {@code customers} root): one bounded page of the
     * CUSTOMER population — the accounts whose primary role is the
     * platform's customer/consumer role ({@code CONSUMER}, the closed
     * {@code UserRole} vocabulary's own customer concept) — as a
     * forward-only keyset scroll in the requested sort (an owning column +
     * the {@code id} tiebreaker — the exact {@code scrollStaffUsers} keyset
     * contract, over the complementary customer-account population of the
     * SAME single identity store: no duplicate identity store, staff
     * accounts excluded by the population's own scoping). ADMIN-enforced at
     * this owning use-case boundary (Saleor requires
     * MANAGE_ORDERS/MANAGE_USERS — the catalog's mapped platform
     * capability, the RBAC mapping row of the divergence registry); the
     * returning page carries the CUSTOMER collection total Saleor's
     * {@code totalCount} field expects (the population's own count, never
     * the roster's).
     *
     * @param authentication the caller, whose ADMIN authority is verified
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap)
     * @return the page of customer account projections plus the customer
     *         population total
     */
    UserAccountPage scrollCustomers(Authentication authentication,
                                    UserAccountSort sort,
                                    org.springframework.data.domain.KeysetScrollPosition position,
                                    org.springframework.data.domain.Limit limit);

    /**
     * P1 (the Saleor {@code permissionGroup} root): the permission-group
     * projection by the native UUID behind the typed global ID. Saleor
     * requires MANAGE_STAFF — enforced here as the platform's server-side
     * ADMIN capability at the owning use-case boundary (the RBAC mapping
     * row of the divergence registry). An unknown but well-formed UUID
     * answers empty (the safe absence the nullable root carries — never an
     * error, never a fabricated group).
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the caller, whose ADMIN authority is verified
     * @return the permission-group projection, empty when no group matches
     */
    java.util.Optional<PermissionGroupView> findPermissionGroupById(UUID id, Authentication authentication);

    /**
     * P1 (the Saleor {@code permissionGroups} root): one bounded page of the
     * permission-group collection as a forward-only keyset scroll in the
     * requested sort (the owning sort field + the {@code id} tiebreaker —
     * the {@code scrollStaffUsers} keyset precedent, over the closed role
     * vocabulary that IS the platform's permission-group collection).
     * ADMIN-enforced as above; the returning page carries the collection
     * total Saleor's {@code totalCount} field expects.
     *
     * @param authentication the caller, whose ADMIN authority is verified
     * @param sort           the executed sort (never {@code null})
     * @param position       the keyset scroll position — the initial
     *                       position, or the position decoded from the
     *                       client's opaque {@code after} cursor
     * @param limit          the bounded page size (the boundary's own cap;
     *                       an unlimited limit answers the whole collection)
     * @return the page of permission-group projections plus the collection
     *         total
     */
    PermissionGroupPage scrollPermissionGroups(Authentication authentication,
                                               PermissionGroupSort sort,
                                               org.springframework.data.domain.KeysetScrollPosition position,
                                               org.springframework.data.domain.Limit limit);
}
