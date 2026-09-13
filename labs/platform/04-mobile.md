# 04 — Mobile: Kotlin Multiplatform + AppAuth/PKCE (isolated platform reference)

> **Tree refs:** client 01-client-types, 02-auth-flows. Generic.
> **Sources:** Kotlin Multiplatform (kotlinlang.org) + Spring Security OAuth2 Client + RFC 8252/7636 — official.
> **Format:** `TYPE | RULE | [evidence] | URL`. **Encoding:** UTF-8.

## 1. What KMP is

- ATTEND | **KMP** (Kotlin Multiplatform) writes **shared code once** for multiple platforms (Android, iOS, JVM) — models, logic, API layer — while each platform keeps its own UI (Jetpack Compose for Android, SwiftUI for iOS) or shares UI via Compose Multiplatform. | [paraphrase] | kotlinlang.org (official)
- ATTEND | `expect`/`actual` mechanism: declare a shared expectation, provide a platform-specific implementation per platform (e.g. Keychain/Keystore storage). | [paraphrase] | kotlinlang.org (official)

## 2. Sharing with the backend

- MUST | Share the API model + API-calling logic + auth logic between iOS and Android from ONE shared module — never duplicate them per platform. | [paraphrase] | kotlinlang.org (official)
- ATTEND | A Java/Kotlin backend team shares the API contract (DTOs/JSON shapes) with the KMP shared module for type fidelity. | [paraphrase] | kotlinlang.org (official)

## 3. Native-platform auth (AppAuth/PKCE)

- MUST | The mobile app is a **Public client** — authorization_code + PKCE, no client secret in the app. | [paraphrase] | client/01-client-types.md · rfc-editor.org/rfc/rfc8252
- MUST | Use a trusted platform auth library (**AppAuth** on iOS/Android) for the authorization_code + PKCE flow. | [paraphrase] | client/01-client-types.md
- MUST | Store tokens in the **device secure storage** (iOS Keychain / Android Keystore / EncryptedStorage) — never in plain app storage. | [paraphrase] | OWASP mobile cheat sheet

## 4. Loopback redirect

- MUST | Use a loopback redirect URI (`127.0.0.1`) or custom scheme for native apps; the redirect URI is matched exactly against pre-registered URIs on the SAS. | [paraphrase] | rfc-editor.org/rfc/rfc8252
- AVOID | Hard-coding secrets or using fixed ports insecurely; prefer loopback with system-assigned available ports. | [paraphrase] | rfc-editor.org/rfc/rfc8252

## 5. Project binding [our-convention]

- MUST | The mobile app consumes the SAME shared backend API as the web app (unified channel); zero backend changes per platform. | [our-convention] | (project constitution / 00-decisions.md)