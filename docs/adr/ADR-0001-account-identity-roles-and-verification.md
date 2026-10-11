# ADR-0001 — Account identity, roles, community membership, and resource authority are four separate facts

- **Status:** Accepted (implemented — plan §Phase 1, decisions D-02/D-03)
- **Date:** 2026-10-10
- **Deciders:** platform backend
- **Governing references (the only authority):** Spring Security reference (Servlet Authorization › Method Security; the `UserDetails` authorities model), Spring Data JPA reference (element collections), Flyway (versioned migrations — "never modify an applied migration"), Spring Modulith (in-transaction domain events).

## Context

The platform serves one account across three doors — the local community, the local market, and the business directory (§1.2). The measured starting state carried a **single scalar role per account** (`users.role` + the `UserRole` enum CONSUMER/PROVIDER/ADMIN), mirrored onto the login-side `auth_authorities` projection. The execution plan's Phase 1 mandates an ADR separating **account identity** from **roles** from **community membership** from **resource authority**, a legacy migration **without automatic privilege elevation**, and security tests for role combinations and cross-ownership access.

## Decision

### 1. Four distinct facts — never one enum, never one table

| Fact | Home | Notes |
|---|---|---|
| **Account identity** | `users` (subject, profile, pseudonymization marker) | Who the account is. Never grants authority by itself. |
| **Roles** | `user_roles` (the set — V171) + the login-side `auth_authorities` projection | What the account may DO platform-wide. A SET, not a scalar. |
| **Community membership** | the community module's own membership records (already module-owned) | Belonging to groups/localities — never derived from roles. |
| **Resource authority** | each owning module's ownership columns + method security | May THIS account act on THAT resource — decided at the resource, not in identity. |

### 2. Roles are a set; the scalar stays as a derived projection

- `User.roles` — `Set<UserRole>` over the `user_roles` join table (official Spring Data JPA element collection, EAGER — cached entities are JDK-serialized). The set is the **source of truth**.
- `users.role` — retained as the **primary-role mirror**, derived deterministically (ADMIN > PROVIDER > CONSUMER), written only by the role mutators in the same transaction. It keeps the Envers `users_aud` trail, `UserSummary.role`, and the data export stable — no consumer breaks. Consumers that need the truth read `getRoles()`.
- An account **always holds at least one role** — the mutators reject an empty set (service: 409; entity: defense in depth).

### 3. One writer, one transaction, one guard

Replace / grant / revoke all land in `UserService.writeRoleProjection`: the `user_roles` set + its primary mirror on the domain side, the framework `UserDetailsManager.updateUser` authority replacement on the login side (the S2/N4/N6 contract, now carrying the **full** target set via the builder's varargs `roles(...)`), issued authorizations killed, caches invalidated, `UserRoleChanged` published in-transaction, one structured audit line. The **last-active-ADMIN counting constraint** guards every change that removes `ROLE_ADMIN` from an enabled account holding it.

Idempotence lives where the set is **computed** (grant's already-held check, revoke's absent-role check). The **replace** command has deliberately NO no-op shortcut: it is the documented reconciliation path for a drifted pair — a same-value replace still re-projects the login side (pinned by the S2/N4/N6 drift tests).

### 4. Verification credentials are evidence — never authority

`verification_credentials` (V171) carries the five plan types (IDENTITY, RESIDENCE, BUSINESS_OWNERSHIP, PROFESSIONAL_QUALIFICATION, OFFICIAL_PUBLISHER) through the lifecycle `PENDING → APPROVED | REJECTED`, `APPROVED → REVOKED`. The decision command publishes exactly one fact (`VerificationCredentialDecided`) and **writes no role store** — the entity has no reference to `UserRole` by construction, and the service tests pin `verifyNoInteractions(userRepository)`. Granting a role remains a separate human administrative act. Self-service submission is one ACTIVE (PENDING/APPROVED) application per type; REJECTED/REVOKED are terminal — a resubmission is a new row, so the history stays complete.

### 5. Legacy migration without elevation

V171 copies each account's **exactly its current scalar role** into `user_roles` — no inference, no elevation, no backfill of trust. Fresh accounts bootstrap from the live login store's **full** authority set (not the first match), degrading to CONSUMER only when the store carries no domain role.

## Consequences

- `hasRole`/`hasAnyRole` gates keep working unchanged — the authority shape (`ROLE_<role>` rows) is untouched; multi-role accounts now carry multiple authorities, which the framework's method security natively supports.
- The admin API gains `GET/POST/DELETE /api/v1/admin/users/{id}/roles`; the existing `PUT .../role` keeps its replace semantics.
- `users.role` is a documented derived column: a future wave may drop it once every consumer reads the set — out of scope here (blast-radius discipline; no user-visible behavior change rides on it).
- Role-set membership is not Envers-audited as a collection; the audit story is the `users_aud` primary-mirror column + the structured audit line every role command writes.

## Alternatives considered

- **Drop `users.role` now** — rejected: it churns the Envers mirror, the cross-module summary, and the export in one stroke for zero behavioral gain.
- **Grant roles automatically on credential approval** — rejected: the plan's own guard (no automatic privilege elevation) and the separation the ADR exists to enforce.
- **A dedicated role-grant entity with temporal validity** — deferred: no measured requirement yet; the audit line + event already carry who/when/actor.
