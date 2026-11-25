# Authorization, identity and data scope

**Priority:** critical for identity, tenant isolation, PII, financial actions, account deletion and administrative privileges.  
**Triggers:** authentication, authorization, role/member management, tenant/org membership, business delegation, admin APIs, PII, content visibility, account erasure, service accounts and background jobs.

**Canonical rules:** B3, B5, C3. **Patterns:** P5. **Evidence:** B1–B4 and G1–G7 from the Nov 10 review; E-09, E-17, E-18, E-43…E-46.

## Invariants to prove

1. The server is the authorization authority. Hiding a button, disabling a form, or filtering a client view is UX—not access control.
2. Every entry point enforces the relevant permission: GraphQL resolver, REST controller, admin action, job, listener, webhook, export, search/read model and direct download path.
3. Distinguish identities and scopes: human actor, authenticated principal, tenant/business organization, delegated manager, target resource, and service principal. Never infer one from another.
4. Delegated business access is scoped, revocable and audited. Personal identity must not become transferable merely because an organization has managers.
5. A background job or event consumer must have an explicit service identity and a narrow capability. No user in the request does not imply unrestricted access.
6. Authorization and ownership must be checked before reading sensitive state or triggering side effects. UI checks mirror the server check but never replace it.
7. Erasure and withdrawal cover every durable copy and every read rail that can re-expose the data: primary rows, mirrors, event payloads, indexes, cache, object storage, signed URLs, exports and derived data.
8. Revocation, last-admin protections and disabled-account restrictions are server-side invariants. Test every endpoint that can create, revoke, delete, transfer or restore access.

## Review questions

- Which actor is acting, on whose behalf, in which tenant/org, on which resource?
- Is the permission check performed on the server before the data lookup/mutation?
- Are there alternate routes, batch operations, listeners, exports or direct object URLs?
- Can a disabled or revoked principal continue through a still-valid token?
- Can the last active administrator be removed?
- Can stale writes, events or signed URLs reanimate or disclose erased data?
- Does the audit record preserve both the acting identity and the delegated/target identity?

## Required proof

Test direct HTTP/API calls, not only UI behavior. Cover unauthorized, wrong-tenant, missing membership, revoked delegation, stale token, disabled account, last-admin, background principal, and read-rail bypass. For erasure, use an adapter/door matrix and prove stale-write rejection and object-storage deletion/URL suppression.

## Saleor reference — what transfers, what does not

The Saleor permissions-access-scopes skill distinguishes app/service scope from user scope and requires server-side route checks; its specific JWT/App Bridge/manifest rules are Saleor-app-specific and are **not** platform rules. Transfer the general principle: list the required permission per feature, enforce it at the server entry point, and mirror it in UI only for usability. Do not add Saleor app tokens or manifest permissions to this platform. See [saleor-ai-adoption-map.md](../saleor-ai-adoption-map.md).

## Anti-patterns

- Treating the presence of an admin UI element as permission.
- Applying one coarse permission to every route without an explicit risk decision.
- Relying on UI visibility as an authorization boundary.
- Checking tenant ownership after returning data or after an external effect.
- Purging only the primary row while read models or object storage still expose the content.
