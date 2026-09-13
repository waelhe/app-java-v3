# 02 — Client Architecture: Unified Channel + BFF per Channel (isolated platform reference)

> **Tree refs:** client 01-client-types, reference 02-web. Generic model + industry examples.
> **Format:** `TYPE | RULE | [evidence] | URL`. `[industry]` = general practice (not official docs). **Encoding:** UTF-8.

## 1. The unified-channel model

- MUST | One shared backend; many client channels (web, mobile, desktop, third-party) consume it via the same unified API. | [industry] | 02-client-architecture.md
- MUST | Give each client channel its own presentation layer: a **Backend-for-Frontend (BFF)** for browser/mobile apps that holds that channel's secrets and shapes responses for it. | [industry] | 02-client-architecture.md
- MUST | A BFF for a web app is a confidential client that holds the client secret and performs the OAuth2 dance; the browser never holds the secret or a long-lived token. | [industry] | client/01-client-types.md
- MUST | A mobile BFF (or the mobile app as a public client) uses Authorization Code + PKCE with no client secret. | [industry] | client/01-client-types.md

## 2. Why this is the global standard

- ATTEND | **Netflix** coined the BFF pattern (2015): one BFF per device type, sharing the backend services. | [industry] | Netflix engineering
- ATTEND | **SoundCloud** published a reference BFF API platform implementation. | [industry] | SoundCloud engineering
- ATTEND | Large platforms (Airbnb, Uber, Amazon, Spotify) follow one shared backend + per-client presentation layers. | [industry] | platform engineering blogs
- MUST | This model gives: single source of truth, consistent security (one token works on all channels), one maintenance point, and independent channel scaling. | [paraphrase] | 02-client-architecture.md

## 3. Fitting a medium/large platform

- MUST | A system with many business modules (marketplace + realestate) and multiple channels is ABOVE the threshold for merging UI into the backend (Thymeleaf/HTMX); use a separate BFF per channel. | [industry] | 02-client-architecture.md
- AVOID | Merging the web UI into the backend for a multi-domain, multi-channel platform — it breaks the unified-API model and the §0.3 backend anchor. | [industry] | 02-client-architecture.md

## 4. Verification note
- BFF is an industry pattern; sources are engineering blogs, not official Spring docs — flagged `[industry]`.