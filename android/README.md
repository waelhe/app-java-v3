# Native Android client — Local community platform

This is the native Android client for the local community platform. The product brand has not been selected, so the app uses a neutral display name. The client is built with Kotlin, Jetpack Compose, Retrofit/Moshi, Android Credential Manager, and AppAuth, outside the root Maven reactor. Native passkey support depends on the companion backend change in PR #555; this Android client PR does not itself alter Spring modules or the database.

## Current app journeys

- Four primary destinations: **الحي / السوق / العقار / دليل الأعمال**.
- Public account registration and email-verification resend in native Compose UI; native passkey sign-in and enrollment via Android Credential Manager when the backend feature is configured; OAuth Authorization Code + PKCE through the system browser remains an explicit fallback. Neighborhood selection/membership uses existing APIs.
- Live neighborhood feed with server-issued post photos, official Android Photo Picker selection (up to five attachments), presigned direct PUT upload and server-side confirmation, Arabic post search, comments, reactions, events and RSVPs, groups and membership, and polls/voting where the backend contracts exist.
- Neighborhood classified listings are separate from the store. The property view filters real listing search by purpose/property type; the business-discovery view searches published service/listing offers and can show returned provider-rating metadata.
- Notification list/read state and the actual neighborhood membership-verification state.
- Arabic-first RTL UI with loading, empty, error, and unavailable-contract states rather than fake server data.

## Known product-contract boundaries

The current backend does **not** expose the complete public storefront/cart/order/delivery/checkout path. The سوق destination must not fabricate a catalog, stock, purchase or order confirmation. Business discovery currently searches published offers; it is not yet a complete provider-profile directory. Android post-photo uploads use the existing media contract: request a presigned URL, stage the selected file within the configured byte limit, PUT directly to HTTPS storage without a bearer token, then confirm the asset through the API. The client default is 10 MiB to match the server default; set `-PmediaMaxUploadBytes=<bytes>` to match a deployment that customizes `MEDIA_MAX_UPLOAD_BYTES`. Photo upload for classified listings and provider/product imagery is not part of this slice. A full inbox/conversation-list contract, device push registration/delivery, reporting/moderation, official institution broadcasts and additional approved discovery rails remain outside this slice. Each must be connected through a verified end-to-end contract before it is presented as working. The backend `NeighborhoodPostView` exposes `lostFoundState` (ACTIVE, FOUND or RESOLVED) and `NeighborhoodEventView` exposes `status` (ACTIVE, CANCELLED or POSTPONED). Android renders only these server-issued values; the owner can close an active lost-and-found report, and RSVP is disabled for events whose status is not ACTIVE or is absent.

## Security and configuration

- The default API base URL and OIDC issuer target the Railway service `https://app-java-v3-production-59bf.up.railway.app`, the environment selected for this test run. Override both with Gradle properties when targeting another environment.
- Android sign-in uses the public client ID `android-community-native`, matched to the Railway service's `OAUTH_PUBLIC_CLIENT_ID`. The GitHub Actions Android build passes this explicitly as `-PoidcClientId=android-community-native`. The backend registers `com.marketplace.android:/oauth2redirect`. The client ID is public configuration; no client secret is shipped in the app.

- Android Credential Manager handles passkey retrieval and creation on supported devices. The app asks the server for WebAuthn options and sends the returned assertion/registration JSON to the server; Android never verifies the assertion locally and does not invent the challenge.
- AppAuth handles the explicit Authorization Code + PKCE fallback in the system browser. No confidential-client secret is packaged in the APK.
- The native passkey OAuth extension grant is `urn:marketplace:params:oauth:grant-type:passkey`; it exchanges a single-use server challenge plus `authenticationResponseJson` at `/oauth2/token` and yields an API access token only. It does not issue an ID token or refresh token, and the app keeps the access token in memory only.
- Access and ID tokens stay in memory only; neither token is written to persistent app storage. On app launch and after an API 401, the client attempts OIDC Authorization Code + PKCE with `prompt=none` to reuse a valid system-browser session. If the provider returns `login_required`, the app returns to interactive sign-in instead of inventing a local session.
- Explicit sign-out clears local tokens and invokes AppAuth's RP-initiated end-session request with the discovered end-session endpoint, ID-token hint when available and client ID. The current server registration configures authorization redirect URIs but does not register a post-logout redirect URI, so the client does not send an unregistered callback URL. If the provider does not return an end-session response to the app, the UI confirms only local sign-out; a smooth automatic return requires a registered post-logout redirect in the server's public-client contract.
- The public client has no refresh-token grant. The resource server validates JWTs locally, so a previously issued access token may remain valid up to its documented 900-second lifetime even after server logout; the app discards its local copy immediately.
- Passkey setup intentionally fails closed until the backend deploy is configured with `marketplace.security.passkeys.rp-id`, `rp-name`, the exact `allowed-origins` from the real Android signing certificate, and that same certificate's SHA-256 fingerprint in `android-sha256-cert-fingerprints`. The server exposes `/.well-known/assetlinks.json` only after real fingerprints are configured. Do not use an invented or debug signing fingerprint for production. If the backend returns `503`, use the fallback login until an operator completes the official Digital Asset Links association.
- The issuer, public client ID, API base URL and redirect URI must match the public OAuth client registered on the backend. Real staging sign-in and end-session behavior still require verification against the configured deployment. No backend or production OAuth settings are changed by this client PR.

## Build and test

Use JDK 17. The checked-in Gradle Wrapper pins Gradle 9.6.0; AGP 9.4.1 is compatible with Gradle 9.6.0, Build Tools 36.0.0 and API 37. The pinned Compose BOM is stable `2026.09.00`. The wrapper automatically provisions the pinned Gradle distribution, keeping local and CI builds on the same Gradle version:

    cd android
    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest

The Android client workflow installs Android SDK platform 37 (Build Tools 36.0.0), runs JVM unit tests, builds the debug APK and uploads it as a workflow artifact. The CI artifact is a debug build, not a production-signed release.

Override environment-specific values without committing them:

From the `android/` directory, override environment-specific values without committing them:

    ./gradlew :app:testDebugUnitTest :app:assembleDebug -PoidcClientId=android-community-native -PoidcRedirectUri=com.marketplace.android:/oauth2redirect -PmediaMaxUploadBytes=10485760

## Code boundaries

- core/network: typed DTOs and API contracts for existing backend endpoints.
- data: one repository owns HTTP and bearer-token injection.
- feature: ViewModel and StateFlow own screen state; Compose renders state and forwards actions.
- No parallel backend, local product database, sample content pretending to be server data, or client secret.

## Official references

- Android app architecture and unidirectional UI state: https://developer.android.com/topic/architecture/recommendations
- Jetpack Compose: https://developer.android.com/develop/ui/compose
- Android Photo Picker (official multi-select flow): https://developer.android.com/training/data-storage/shared/photo-picker
- Coil 3 Compose `AsyncImage` and network setup: https://github.com/coil-kt/coil/blob/main/docs/getting_started.md
- Android SDK setup action: https://github.com/android-actions/setup-android
- Gradle Wrapper and build-version reproducibility: https://docs.gradle.org/current/userguide/gradle_wrapper.html
- Kotlin releases and Compose compiler plugin: https://github.com/JetBrains/kotlin/releases
- Retrofit: https://github.com/square/retrofit
- OkHttp: https://github.com/square/okhttp
- Kotlin coroutines (Android and tests): https://github.com/Kotlin/kotlinx.coroutines
- Android Credential Manager sign-in with passkeys: https://developer.android.com/identity/passkeys/sign-in-with-passkeys
- Android Credential Manager passkey creation: https://developer.android.com/identity/passkeys/create-passkeys
- AndroidX Credentials release notes (1.6.0 stable): https://developer.android.com/jetpack/androidx/releases/credentials
- Android Identity Samples: https://github.com/android/identity-samples
- Native OAuth 2.0 guidance: https://datatracker.ietf.org/doc/html/rfc8252
- AppAuth Android: https://github.com/openid/AppAuth-Android
