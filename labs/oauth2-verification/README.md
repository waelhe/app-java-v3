# labs/oauth2-verification — OAuth2 Local Bootstrap Verification Station (isolated)

> **Purpose:** the local verification station record — how both OAuth2 clients (web + mobile) were registered on a local server via the official framework path (`RegisteredClientRepository.save` + `OAuth2ClientSecretInitializer`/`OAuth2PublicClientInitializer`) and proven live end-to-end (S2 confidential + public PKCE refreshless).
> **Owners:** branch-attached session record (`governance/d012-scope`); `PROJECT_MAP.md` keeps only a pointer to this family.
> **Load order:** `oauth2-local-bootstrap-runbook.md` (this family's single runbook — §0-§7 reproducible procedure + §8 session record 2026-09-14 with measured values: V51=`-38705425` fingerprint, `expires_in=899`, RS256 JWKS kid `3fe86f53-…`).
> **Sources:** `docs/security/oauth2-client-bootstrap-spec.md` (governing doc), the initializer classes in `marketplace-platform-infra`, live DB reads from `oauth2_registered_client`.
> **Isolation:** `labs/` only; never touches `docs/governance/`.