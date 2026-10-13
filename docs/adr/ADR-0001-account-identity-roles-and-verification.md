# ADR-0001 — Account identity, roles, community membership, and resource authority are four separate facts

- **Status:** Accepted (implemented — the unified plan §10, D-03 + §6.5; the #543 assignment model, measured through its CodeRabbit round)
- **Date:** 2026-10-10 (rewritten 2026-10-13 to the implemented shape — the cascade's earlier `user_roles` element-collection sketch is superseded, see Alternatives)
- **Deciders:** platform backend
- **Governing references (the only authority):** Spring Security reference (Servlet Authorization › Method Security; the `UserDetails` authorities model; `JdbcUserDetailsManager`'s authorities-by-username contract), Spring Data JPA reference (the `@SoftDelete` filter; Envers auditing), Flyway (versioned migrations — "never modify an applied migration"), Spring Modulith (in-transaction domain events), PostgreSQL (partial unique indexes; `NOT VALID → VALIDATE`).

## Context

The platform serves one account across three doors — the local community, the local market, and the business directory (§1.2). The measured starting state carried a **single scalar role per account** (`users.role` + the `UserRole` enum CONSUMER/PROVIDER/ADMIN), mirrored onto the login-side `auth_authorities` projection. The Phase-1 mandate: separate **account identity** from **roles** from **community membership** from **resource authority**, a legacy migration **without automatic privilege elevation**, and security tests for role combinations and cross-ownership access.

## Decision

### 1. Four distinct facts — never one enum, never one table

| Fact | Home | Notes |
|---|---|---|
| **Account identity** | `users` (subject, profile, pseudonymization marker) | Who the account is. Never grants authority by itself. |
| **Roles** | `user_role_assignments` (V187) + the `auth_effective_authorities` union (V188) over the login-side `auth_authorities` | What the account may DO platform-wide. Several of the closed V1 vocabulary at once, as independent ACTIVE rows. |
| **Community membership** | the community module's own membership records (already module-owned) | Belonging to groups/localities — never derived from roles. |
| **Resource authority** | each owning module's ownership columns + method security | May THIS account act on THAT resource — decided at the resource, not in identity. |

### 2. Roles are assignments; the scalar primary stays untouched

- `user_role_assignments` (V187) — one ACTIVE row per (account, role) pair (the partial unique index `uq_user_role_assignments_one_active`), carrying its own lifecycle: `granted_at`, `granted_by`, `source` (`BACKFILL` | `ADMIN` — the closed writer vocabulary), `revoked_at` (a re-grant after a revoke inserts a FRESH row — the history stays), and the Envers mirror.
- `users.role` — **deliberately untouched** (the Phase-1 letter): the column keeps its NOT NULL + V1 CHECK, and `UserService.updateUserRole` keeps its S2/N4/N6 two-store choreography maintaining it and its `auth_authorities` projection. The assignment model is the **additive multi-role layer**, not a replacement.
- The **effective authorities** (V188) — a VIEW, not a second write path: `auth_authorities UNION` the active assignments' `ROLE_<role>` rows, wired as the ONE authorities query the login chain already reads (`JdbcUserDetailsManager.setAuthoritiesByUsernameQuery`). An active assignment IS an authority at the next token mint; a revoked one is not (in-flight access tokens die by their 900s TTL; the refresh resurrection is killed by the revoke's authorization-row deletion; live sessions by the `UserRoleAssignmentRevoked` consumer at AFTER_COMMIT).

### 3. Two writers, two commands, one guard each

- **The primary role** changes only through `UserService.updateUserRole` — the two-store command (the domain column + the login-side projection replaced through the framework `UserDetailsManager.updateUser`), with the last-active-ADMIN counting constraint guarding every removal of `ROLE_ADMIN` from an enabled account holding it.
- **A granted-beyond-primary role** changes through `RoleAssignmentService` (the A-07 three-layer pattern's own `@PreAuthorize("hasRole('ADMIN')")`): the grant answers the duplicate-active pair with the honest 409 (the partial unique index is the concurrent-insert backstop); the **revoke refuses the account's primary role outright** — its assignment is the choreography's own row, and revoking it would leave the authority alive through the union's login-side leg while the displayed roles and the effective authorities disagree (the CodeRabbit #543 round). Every command writes its structured audit line (actor, target, role, action, source).

### 4. Trust attestations are evidence — never authority

`verification_attestations` (V189) carries the four §6.5 trust types (`VERIFIED_LOCAL_MEMBER`, `VERIFIED_BUSINESS`, `COMMUNITY_ENDORSEMENT`, `VERIFIED_SOURCE` — never one conflated «موثق» flag) through the lifecycle `PENDING → GRANTED | REJECTED`, `GRANTED → REVOKED`, with the evidence reference required at submission. One GRANTED attestation per (subject, type) — the partial unique index `uq_verification_attestations_one_active`; **one PENDING at a time** for the same pair (the service's 409 — the CodeRabbit #543 round: the review queue never carries a stale twin); a fresh request after REJECT/REVOKE is legal (the pair is freed). Deciding an attestation **writes no role store** — supervision privileges are never gained or lost through a badge or an endorsement. Self-service submission and the own history read ride `/me/trust-attestations`.

### 5. Legacy migration without elevation

V187's backfill derives each assignment from the account's **own login-side `auth_authorities` rows** (restricted to the closed `ROLE_CONSUMER`/`ROLE_PROVIDER`/`ROLE_ADMIN` vocabulary) — never `users.role` (the CodeRabbit #543 round): a backfilled assignment can never exceed the authority the account's login rows already carry, so the Phase-1 gate «الحسابات القائمة تحفظ وصولها بلا توسيع صلاحيات» holds **by construction** — on the maintained state (the projection == `users.role`) the union collapses to the pre-migration authority set byte for byte, and on a drifted pair the backfill preserves the login-side truth without expansion. Soft-deleted memberships are not backfilled (no access left to preserve).

## Consequences

- `hasRole`/`hasAnyRole` gates keep working unchanged — the authority shape (`ROLE_<role>` rows) is untouched; multi-role accounts carry multiple authorities, which the framework's method security natively supports.
- The admin API gains `GET/POST/DELETE /api/v1/admin/users/{id}/roles` (the `IdentitySpi` delegation over `RoleAssignmentService`); the existing `PUT .../role` keeps the primary's two-store semantics.
- The account's own reads ride `/me/roles` (the active assignment set) and `/me/trust-attestations`.
- `users.role` stays the primary-role mirror for the Envers trail, `UserSummary.role`, and the data export — a future wave may retire it once every consumer reads the assignment set (out of scope; blast-radius discipline).
- The audit story: the assignment and attestation rows ARE the audit records (who granted, when, why source; who reviewed, when) + the structured audit line every command writes.

## Alternatives considered

- **The `user_roles` element-collection set (the cascade's original V183 sketch) — superseded by this ADR's implemented shape.** The set model rewrote `users.role` on every grant/revoke (deriving the primary mirror ADMIN > PROVIDER > CONSUMER), widening every command's blast radius onto the legacy column, its Envers trail, and the login projection in one transaction; it carried no grant history (a revoked-then-regranted role left no trace), and its eager element collection rode every cached `User` entity. The assignment model is additive (the primary and its projection untouched), lifecycle-bearing (`granted_at`/`revoked_at`/`source`, the re-grant inserts a fresh row), and read-side honest (the V188 view, no materialized copy).
- **Drop `users.role` now** — rejected: it churns the Envers mirror, the cross-module summary, and the export in one stroke for zero behavioral gain.
- **Grant roles automatically on attestation approval** — rejected: the plan's own guard (no automatic privilege elevation) and the separation this ADR exists to enforce.
