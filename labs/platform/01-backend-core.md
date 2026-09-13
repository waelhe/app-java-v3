# 01 — Shared Backend Core (isolated platform reference)

> **Tree refs:** reference 05-modulith, 04-data, 03-security. Generic — real details in `00-decisions.md`.
> **Format:** `TYPE | RULE | [evidence] | URL`. **Isolation:** generic only. **Encoding:** UTF-8.

## 1. The shared backend

- MUST | A shared backend is a unified domain (or set of services) that ALL client channels consume; it is the single source of truth for business logic and data. | [paraphrase] | 02-client-architecture.md
- MUST | Keep the backend modular: each business capability is a module (Modulith) with a clear public API; modules interact via events + public APIs, not internal bean injection. | [paraphrase] | reference/05-modulith.md
- MUST | Enforce module boundaries in CI (e.g. `ApplicationModuleVerification`) to prevent illegal cross-module dependencies. | [paraphrase] | reference/05-modulith.md
- MUST | Expose a unified API surface (REST + OpenAPI) that all channels use identically — no per-channel copies of business logic. | [paraphrase] | reference/02-web.md

## 2. "Backend anchored" principle (§0.3)

- MUST | The backend/core/data are unified and closed; a new client = a presentation layer/config + test over the shared API, NOT new backend code/module/dependency. | [our-convention] | (project constitution)
- MUST | Register clients via `RegisteredClientRepository` (env → DB) — client-id, redirect-uri, scopes, grant types; zero code change to the backend. | [paraphrase] | reference/03-security.md §5
- MUST | Configure CORS in env for browser clients; mobile (non-browser) needs no CORS. | [paraphrase] | reference/02-web.md §4

## 3. Data & contracts

- MUST | Single data store shared by all channels (relational core + cache + object storage); all channels read/write via the unified API. | [paraphrase] | reference/04-data.md · reference/08-ops.md
- MUST | Use RFC 9457 Problem Details for all API errors so every channel decodes them identically. | [paraphrase] | reference/09-api-web.md §1
- MUST | Version the API (header/path/media-type) so channels evolve independently without breaking the contract. | [paraphrase] | reference/02-web.md §5

## 4. Verification note
- This file is generic; apply the real module map and auth evidence from `00-decisions.md`.