# 05 — Auth & Identity: OIDC/OAuth2 Standard, Subject-Neutral, Pseudonymization, Migration (isolated platform reference)

> **Tree refs:** reference 03-security §4-5, client 02-auth-flows, 03-token-handling; EDPB/GDPR official.
> **Sources:** Spring Security official + rfc-editor (6749/8252/7636/9449/7009) + EDPB + GDPR — official.
> **Format:** `TYPE | RULE | [evidence] | URL`. **Encoding:** UTF-8.

## 1. OIDC/OAuth2 standard

- MUST | All clients speak **OIDC/OAuth2** through the protocol (issuer-uri/discovery), not through provider-specific code — the issuer is a configuration. | [quote] | servlet/oauth2/client/authorization-grants.html
- MUST | Keep the `issuer` dynamic (env) so switching providers is a config change, not a rewrite. | [paraphrase] | 00-decisions.md §3.1 (project evidence)
- MUST | Use a **provider-neutral `sub`** — never bind stored identity to a provider-specific subject. | [paraphrase] | servlet/oauth2/client/authorized-clients.html

## 2. Pseudonymization (EDPB/GDPR compliant)

- MUST | Pseudonymization via **cryptographic algorithms** (HMAC) is one of the officially-classed replacement procedures. | [quote] | EDPB Guidelines 01/2025 §87
- MUST | The derivation key is "additional information" that must be **kept separately** (GDPR Art. 4(5)) — env/secret-manager only, never in DB/Git/logs. | [paraphrase] | GDPR Art. 4(5)
- MUST | Use a full-strength HMAC (e.g. HMAC-SHA256, no truncation); keep derivation deterministic so the mapping is stable and verifiable. | [paraphrase] | 00-decisions.md §3.2 (project evidence)
- MUST | Support key rotation via a keyring (active key + retained previous keys) so re-registration guards keep matching after rotation. | [paraphrase] | 00-decisions.md §3.2 (project evidence)

## 3. Client-lookup and session

- ATTEND | Resolve the current user by the raw JWT `sub` from the token (the identity lookup), while the pseudonymized derivation applies to the pseudonymization/account path — two separate lanes. | [paraphrase] | 00-decisions.md §3.3 (project evidence)

## 4. Provider migration (future-proof)

- ATTEND | Because both the own system and an external provider speak standard OAuth2/OIDC, switching is a config change (`issuer-uri`) — provided the `sub` stays provider-neutral and client code reads provider settings dynamically. | [paraphrase] | servlet/oauth2/client/authorization-grants.html
- ATTEND | If identity is **derived** (pseudonymized) from the current issuer's `sub`, moving to an external provider changes the derived identities — **migration requires an identity-mapping/relocation plan**, not just a config swap. | [paraphrase] | 00-decisions.md §3.3 (project evidence)

## 5. Verification note
- OIDC/claims rules from official docs; pseudonymization compliance from EDPB/GDPR (see details in `00-decisions.md`).