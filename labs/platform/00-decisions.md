# 00 — Real Project Decisions & Evidence (isolated, actual data)

> **This is the ONLY file with real project details** (per the isolation rule). It records the four
> locked decisions of phase 0, their rationale, and the actual evidence verified in the code.
> Sources cited where they are official; project facts are labeled `[repo-fact]` (verified from the code).

## 1. Decision set (phase 0 — locked)

| # | Decision | Value | Evidence |
|---|---|---|---|
| D1 | Mobile | **Kotlin Multiplatform (KMP)** | Chosen to share models/logic/API between iOS & Android; Kotlin/Gradle matches the Java/Kotlin backend. |
| D2 | Web | **Next.js + BFF** | A separate web app whose server (BFF) holds the secret and consumes the same shared API as mobile. |
| D3 | Identity/accounts | **Own backend (Spring Authorization Server)** | Already built as OAuth2/JWT; keeps full control + GDPR/PDPL compliance; enables easy provider migration (see D3-evidence). |
| D4 | Environment | **Local (localhost) first**, then Railway | Fastest iteration; then real-device/native tests and production on Railway. |

## 2. Backend reality (verified in repo — `[repo-fact]`)

- **19 Maven modules** (root `pom.xml`): marketplace-shared, marketplace-platform-infra, marketplace-identity, marketplace-catalog, marketplace-booking, marketplace-payments, marketplace-pricing, marketplace-reviews, marketplace-messaging, marketplace-search, marketplace-provider, marketplace-availability, marketplace-notifications, marketplace-ledger, marketplace-disputes, marketplace-media, marketplace-geo, marketplace-realestate, marketplace-app.
- This is a medium/large, multi-domain system (marketplace + realestate) — **above the threshold where a UI merged into the backend (Thymeleaf/HTMX) is suitable**. Hence D2 (Next.js+BFF).

## 3. Auth/identity evidence (verified in repo — `[repo-fact]`)

### 3.1 OIDC standard compliance
- Issuer is **dynamic** via env: `issuer: ${AUTH_SERVER_ISSUER:http://localhost:8080}` (`marketplace-app/src/main/resources/application.yml`).
- OIDC discovery + JWKS endpoints are served (`/.well-known/openid-configuration`, `/oauth2/jwks`).
- JWT decoded via `NimbusJwtDecoder` (issuer/audience/expiry validation) — see `labs/reference/03-security.md`.

### 3.2 Subject pseudonymization — officially compliant
- Class `com.marketplace.shared.security.SubjectPseudonymizer` derives `"anon-" + hex(HMAC-SHA256(K_env, subject))`.
- Its javadoc cites **EDPB Guidelines 01/2025 §87**: "Two classes of replacement procedures are commonly applied as pseudonymising transformations: cryptographic algorithms and lookup tables." — this implementation is the **cryptographic algorithms** class.
- Key is the "additional information" of **GDPR Art. 4(5)**, kept **separately** — env-only channel `PSEUDONYMIZATION_HMAC_KEY`, never in DB/Git/logs.
- Deterministic, full 256-bit HMAC (no truncation), key rotation via a keyring (`PSEUDONYMIZATION_HMAC_PREVIOUS_KEYS`).

### 3.3 Migration caveat (decision D3 implication)
- Although OIDC-compliant (easy issuer swap), the pseudonymized subject is **derived from the current issuer's `sub`**. Moving to an external provider changes `sub` → different derived identities. **Provider migration therefore requires an identity-mapping/relocation plan**, not just a config swap.
- Authentication (`IdentityUserProvider.getCurrentUserId`) resolves the user by the **raw `sub`** from the JWT (`userRepository.findBySubject(subject)`); the `anon-…` derivation applies to the pseudonymization/account path, not to every-request auth.

## 4. Global-model fit

The chosen architecture (unified backend + BFF per channel) matches the model used by Netflix (BFF coined by Netflix/SoundCloud) and other large platforms — see `02-client-architecture.md`. This is `[industry]` knowledge, not an official Spring doc.

## 5. Verification note
- Project facts verified live from the repo (branch `governance/d012-scope`) on 2026-09-12. Official sources as cited in the family files (`labs/reference/`, `labs/client/`, `labs/platform/`).