# Public Client Authentication — PKCE, No API Keys (L29)

> **Scope of this document:** it pins the standing security decision for the
> *public* API client class (native mobile / SPA), as required by the
> feature-expansion roadmap §5 L29(ج) and closing debt **D5** (roadmap §8).
> It is a decision record, not a tutorial: every claim below carries its
> evidence from the code or the official sources already cached in this
> repository.

## The decision (pinned)

**The public client authenticates end users with Authorization Code + PKCE,
carries no client secret, receives no refresh tokens, and there are no API
keys for machine clients.** Any future client that needs secret-less,
key-less access joins this exact class through configuration — not new code
(`docs/security/client-hosting-strategy-plan.md` §4).

## What this means, concretely

| Property | Value | Evidence |
|---|---|---|
| Client authentication | `none` — the client proves nothing to the token endpoint | `OAuth2PublicClientInitializer.java:157` (`ClientAuthenticationMethod.NONE`) |
| Grant type | `authorization_code` **only** | `OAuth2PublicClientInitializer.java:158` |
| PKCE | mandatory (`requireProofKey(true)`) — RFC 9700 §2.1.1, protects against code interception and the PKCE downgrade attack | `OAuth2PublicClientInitializer.java:175` + SAS how-to |
| Refresh tokens | **never issued** — SAS "will not issue refresh tokens for a public client" (gh-297) | `OAuth2PublicClientInitializer.java:180-190` (no refresh-token settings by design) |
| Access token TTL | 900 seconds | `OAuth2PublicClientInitializer.java:187` |
| Authorization code TTL | 300 seconds | `OAuth2PublicClientInitializer.java:188` |
| Consent | required | `OAuth2PublicClientInitializer.java:176` |
| Redirect URIs | environment-driven (`OAUTH_PUBLIC_CLIENT_REDIRECT_URIS`, custom-scheme per RFC 8252) | `OAuth2PublicClientInitializer` bootstrap section |

## Why no API keys

1. **The one real consuming client class is the public app** — a
   person-interacting native/SPA client. PKCE + short-lived access tokens is
   the official recommendation for exactly this class; a long-lived static
   API key would be a strictly weaker credential (bearer, no user context,
   no expiry).
2. **A machine client, if one is ever needed, is a different class** — the
   confidential pattern (`client_credentials`, `client_secret_basic`) already
   exists in the same codebase (`OAuth2ClientSecretInitializer`) and is
   provisioned through the standard `RegisteredClientRepository.save` path
   (env → DB). That path is an operator decision, not a code change.
3. **The verification cost of API keys is real** — key issuance, rotation,
   scoping and revocation would each need new surface area; today the system
   has zero of that surface, and the roadmap keeps it that way until a
   documented need appears.

**Closing point (D5):** the first real automated client that needs
machine-to-machine access reopens this decision with its documented
requirement — until then, the pinned pattern above is the contract.

## How a client uses this (the flow, in brief)

1. Open the authorize endpoint with `response_type=code`,
   `client_id` = the public client id, a `code_challenge` (S256), and the
   registered redirect URI.
2. The user authenticates (form login) and consents.
3. Exchange the returned code at the token endpoint with
   `code_verifier` — **no client authentication** (no secret header).
4. The access token (JWT, 900s) is sent as `Authorization: Bearer …` to the
   resource APIs. When it expires, the user re-authenticates through the
   same flow; there is no refresh grant to fall back to.

The full end-to-end proof of this flow — including the negatives
(no `code_challenge` ⇒ 302 error, Basic auth ⇒ 401 `invalid_client`,
refresh grant ⇒ 401) — is pinned in
`PublicPkceClientGateIntegrationTest`.

## Related documents

- `docs/security/client-hosting-strategy-plan.md` §4 — the client pattern
  matrix (public vs confidential) and what each costs the backend.
- `docs/security/oauth2-client-bootstrap-spec.md` — the bootstrap mechanics
  (env → DB) both client initializers follow.
- `docs/api/error-contract.md` — the RFC 7807 problem contract every
  protected endpoint answers with on auth failures (401/403).
- Rate limiting of public write endpoints (this layer, L29):
  `application.yml` → `resilience4j.ratelimiter.instances` — 429 answers
  carry the same problem contract (`RL-001`).
