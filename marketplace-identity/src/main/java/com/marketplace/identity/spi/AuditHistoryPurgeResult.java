package com.marketplace.identity.spi;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-4 — the audit
 * history purge): the measured outcome the administrative surface
 * answers. Two independent counts, because the plan's purge option names
 * two cleanses: the column scrub and the mirror deletion.
 *
 * @param scrubbedRows        the number of {@code created_by}/
 *                            {@code updated_by} cells nulled across every
 *                            discovered table (per-statement row counts —
 *                            a row carrying the subject in both columns
 *                            counts twice, one per statement; base tables
 *                            and Envers mirrors alike)
 * @param usersAudRowsDeleted the number of {@code users_aud} revisions
 *                            deleted for the user (wholesale — the
 *                            plan's letter)
 */
public record AuditHistoryPurgeResult(int scrubbedRows, int usersAudRowsDeleted) {
}
