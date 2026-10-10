package com.marketplace.identity;

/**
 * D-03 (community platform execution plan Stage 1): the provenance of a
 * role grant — "how did this account come to hold this role". Stored on
 * every {@link AccountRole} row and mirrored in the Envers audit trail, so
 * the plan's "verification types distinct in APIs, storage, audit, and
 * contracts" criterion starts from an honest provenance record instead of
 * an indistinguishable blob of grants.
 *
 * <p>Values are stored as VARCHAR (the {@code source} column, length 40) —
 * the stored-name vocabulary convention the shared events already speak
 * (the {@code UserRoleChanged} precedent: stored names cross boundaries,
 * owning-module enums stay home).
 */
public enum RoleGrantSource {

    /** V182 backfill — the role as it stood on the {@code users.role} mirror. */
    LEGACY_MIRROR,

    /** V182 backfill — the role as a (possibly drifted) auth_authorities row. */
    LEGACY_AUTHORITIES,

    /** The automatic CONSUMER grant at registration — no administrator involved. */
    REGISTRATION,

    /** An administrator's grant/revoke surface (the actor is on the row). */
    ADMIN_GRANT,

    /** The {@code updateUserRole} replace surface (the set rewritten to one role). */
    REPLACE
}
