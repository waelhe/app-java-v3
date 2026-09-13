# labs/client — Client Application Integration Reference (isolated)

> **Scope:** client apps (web / mobile / native / service) that connect to an OAuth2/JWT-secured backend.
> **Sources (official only):** Spring Security 7.1.1 OAuth2 Client + Spring Authorization Server + RFC 6749/7519/8252/7636/9449/9457 + OWASP — verified live 2026-09-12.
> **Isolation:** no internal repo data, no current system, no Phase-1 carryover. **Encoding:** UTF-8.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. `[quote]`=verbatim · `[paraphrase]`=faithful restatement.

## The fundamental axis — Client Type

| Client type | Can keep a secret? | Model | Flow |
|---|---|---|---|
| **Confidential** | yes | server-side web app, backend service | authorization_code + client_secret · client_credentials |
| **Public** | no | SPA (browser), mobile/native app | authorization_code + **PKCE**, no client secret |

> Official: "Public Clients are supported by using Proof Key for Code Exchange (PKCE). If the client is running in an untrusted environment (such as a native application or web browser-based application) and is therefore incapable of maintaining the confidentiality of its credentials, PKCE is automatically used when: client-secret is omitted (or empty)… or client-authentication-method is set to none."
> — spring-security servlet/oauth2/client/authorization-grants.html

## Files

| File | Content |
|---|---|
| `01-client-types.md` | Confidential vs Public; which model applies to which app. |
| `02-auth-flows.md` | Authorization Code + PKCE / Client Credentials / Refresh / Logout / OIDC. |
| `03-token-handling.md` | Obtain / refresh / revoke / failure-removal / secure storage (BFF). |
| `04-api-integration.md` | RestClient/WebClient interceptors + RFC 9457 contract + CORS. |
| `05-security-practices.md` | PKCE / exact redirect match / DPoP / SPA security (OWASP). |

## Usage rules

1. Load a file on demand; every item carries its official source URL.
2. Distinguish framework rule (`[quote]`/`[paraphrase]`) from project convention (`[our-convention]`).
3. Applies to the project's §0.3 "backend anchored" principle: a client = configuration + test via `RegisteredClientRepository.save` + CORS; zero new backend code.