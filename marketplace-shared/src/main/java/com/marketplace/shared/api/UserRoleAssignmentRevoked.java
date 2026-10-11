package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Phase 1 (the unified plan §10, D-03) — published by the identity
 * module's multi-role REVOKE command inside its own transaction: the
 * domain fact that one of an account's active role assignments
 * ({@code user_role_assignments}, V182) was withdrawn.
 *
 * <p><b>Why the REVOKE leg alone carries an event (the named-consumer
 * rule — the measured «fire into the void» defect of consumerless
 * publications):</b> a GRANT is fail-CLOSED for every live surface —
 * live sessions and in-flight tokens simply lack the new authority, so
 * nothing stale keeps working that should not. A REVOKE is fail-OPEN on
 * exactly two carriers: a live session whose cached authorities still
 * contain the withdrawn role, and a refresh token whose stored
 * principal re-mints the old authority set. The refresh carrier dies
 * with the command itself (the L23 documented basis — the authorization
 * rows are deleted in the same transaction); the session carrier is
 * what this event closes, consumed by
 * {@code AccountStatusSessionInvalidator} (the R8 consumer, the same
 * asymmetry {@code AccountStatusChanged} rides: the enable/grant leg
 * expires nothing by design).
 *
 * <p><b>The authority's own repair path is the V183 view:</b> the
 * effective-authorities union drops the revoked row at the next
 * login/token mint — this event covers the BEFORE-next-login window.
 *
 * <p>The Modulith house pattern ({@code UserRoleChanged} verbatim):
 * identifiers and stored names only, published inside the command's
 * transaction so the publication registry entry commits atomically with
 * the revoke — a consumer failing AFTER_COMMIT leaves the registry entry
 * incomplete for the framework's resubmission instead of losing the
 * fact. {@code role} carries the stored {@code CONSUMER/PROVIDER/ADMIN}
 * name (the shared-api String-vocabulary rule — no identity-domain type
 * crosses the boundary).
 */
public record UserRoleAssignmentRevoked(
        UUID userId,
        String username,
        String role
) {
}
