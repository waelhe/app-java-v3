# 01 — Client Types: Confidential vs Public (isolated client reference)

> **Tree refs:** 3.4 (OAuth2 Client) + RFC 6749 §2.1 + RFC 8252 (native apps) + RFC 7636 (PKCE).
> **Sources:** `https://docs.spring.io/spring-security/reference/servlet/oauth2/client/` (7.1.1) + `https://www.rfc-editor.org/rfc/` — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. **Isolation:** no internal repo data. **Encoding:** UTF-8.

## 1. The axis

- MUST | Classify the client as **Confidential** (can keep a secret: server-side web app, backend service) or **Public** (cannot: SPA, mobile/native app). | [paraphrase] | rfc-editor.org/rfc/rfc6749 (Client Types, §2.1)
- ATTEND | Public clients are unsupported for the plain Authorization Code grant without PKCE; use `authorization_code` + PKCE with no client secret. | [quote] | servlet/oauth2/client/authorization-grants.html
- MUST | Configure a public client with `client-authentication-method: none` and no `client-secret`; PKCE then applies automatically (SAS requires PKCE for authorization_code by default). | [paraphrase] | servlet/oauth2/client/authorization-grants.html · servlet/oauth2/authorization-server/core-model-components.html

## 2. Which model per app

- MUST | Server-side web app → Confidential → `authorization_code` + `client_secret` (client auth). | [paraphrase] | servlet/oauth2/client/authorization-grants.html
- MUST | SPA / browser app → Public → `authorization_code` + PKCE, no secret; prefer the **Backend-for-Frontend (BFF)** pattern so the browser never holds the client secret. | [paraphrase] | servlet/oauth2/client/authorization-grants.html · OWASP SPA cheat sheet
- MUST | Mobile / native app → Public → `authorization_code` + PKCE + a loopback (`127.0.0.1`) or custom-scheme redirect URI; no secret in the app. | [quote] | rfc-editor.org/rfc/rfc8252 (OAuth 2.0 for Native Apps)
- MUST | Machine-to-machine / backend service → Confidential → `client_credentials` (token obtained directly from the Token Endpoint). | [paraphrase] | servlet/oauth2/client/authorization-grants.html
- ATTEND | Long-running sessions → `refresh_token` flow, only when an authorized-client provider is available to perform it. | [paraphrase] | servlet/oauth2/client/authorization-grants.html

## 3. PKCE (RFC 7636)

- MUST | Public clients use PKCE (`code_challenge`/`code_verifier`); SAS enables PKCE by default for all `authorization_code` clients. | [quote] | servlet/oauth2/authorization-server/core-model-components.html · rfc-editor.org/rfc/rfc7636
- AVOID | Disabling PKCE for a public client — it defeats the protection against authorization-code interception. | [paraphrase] | rfc-editor.org/rfc/rfc7636

## 4. Project binding [our-convention]

- MUST | Per §0.3 "backend anchored": a new client = configuration + test via `RegisteredClientRepository.save` + CORS env; zero new backend code/module/dependency. | [our-convention] | (project constitution)