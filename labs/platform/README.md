# labs/platform — Unified Platform Reference (isolated, AI-consumable)

> **Scope:** a fully integrated platform — shared backend + web + mobile — so any AI can understand
> and build it without internal data or memory. Grounded in official docs where available; clear
> citation where a pattern is general industry knowledge.
> **Sources:** Spring Security/Authorization Server, Next.js, Kotlin Multiplatform, RFC 6749/7519/8252/7636/9449/9457, OWASP — official; platform-architecture examples (Netflix/SoundCloud) are industry knowledge, flagged as such.
> **Isolation:** no internal repo data in the family files; real project details live ONLY in `00-decisions.md`.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. `[quote]`=verbatim · `[paraphrase]`=faithful restatement · `[industry]`=general practice.
> **Encoding:** UTF-8.

## Files

| File | Content |
|---|---|
| `00-decisions.md` | **Real project details** — the four locked decisions + rationale + evidence. |
| `01-backend-core.md` | Shared Spring/Modulith backend: what it is, its boundaries, §0.3. |
| `02-client-architecture.md` | Global model: unified channel + BFF per channel (Netflix/SoundCloud). |
| `03-web.md` | Next.js + BFF — security, integration, token handling. |
| `04-mobile.md` | Kotlin Multiplatform + AppAuth/PKCE — sharing, security. |
| `05-auth-identity.md` | OIDC/OAuth2 standard + subject-neutral + pseudonymization + migration. |
| `06-security-contracts.md` | RFC 9457 / JWT / CSRF / CORS / OWASP SPA. |
| `07-environment.md` | Local vs Railway + run/deploy plans. |
| `08-build-roadmap.md` | Sequential execution plan (register clients → web → mobile → test). |

## Usage rules

1. Family files are generic (any AI can follow them); real details are isolated to `00-decisions.md`.
2. Every rule carries `[evidence]` + source URL; `[industry]` items are flagged, not presented as official.
3. Applies the project's §0.3 "backend anchored" principle: a client = config + test via `RegisteredClientRepository.save` + CORS; zero new backend code.