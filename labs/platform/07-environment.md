# 07 — Environment: Local vs Railway + Run/Deploy Plans (isolated platform reference)

> **Tree refs:** reference 08-ops, 06-build. Generic.
> **Format:** `TYPE | RULE | [evidence] | URL`. **Encoding:** UTF-8.

## 1. Local (localhost) — development first

- MUST | Develop first on localhost: fastest iteration, simple CORS, local issuer, no cost. | [industry] | 07-environment
- ATTEND | For KMP emulators, localhost is reachable via the emulator loopback (e.g. Android emulator `10.0.2.2`); real devices need a routable URL. | [industry] | 07-environment
- MUST | Read secrets from env locally (`.env`/export), mirroring the production channel separation. | [paraphrase] | reference/09-api-web.md §5

## 2. Railway — real-device tests + production

- MUST | Deploy the backend to Railway for real-device client tests (the mobile device needs a public URL) and for production. | [industry] | 07-environment
- ATTEND | Behind a TLS-terminating proxy the issuer MUST be the public https origin (env `${AUTH_SERVER_ISSUER}`), not localhost. | [paraphrase] | 00-decisions.md §3.1 (project evidence)
- MUST | Update client configs to the public issuer/CORS origins when moving from local to Railway. | [paraphrase] | client/01-client-types.md

## 3. Run order

- MUST | Local → real-device → production: develop locally, test real clients against Railway, then release. | [industry] | 07-environment
- MUST | Keep packaging lean (reference/08-ops) and never package devtools in production images. | [paraphrase] | reference/08-ops.md §4

## 4. Verification note
- Environment strategy from the platform plan; technical rules cross-referenced from `labs/reference/`.