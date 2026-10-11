# Native Android client — Local community platform

This is the native Android client for the local community platform. The product brand has not been selected, so the app uses a neutral display name. The client is built with Kotlin, Jetpack Compose, Retrofit/Moshi and AppAuth, outside the root Maven reactor. It adds no Spring module and makes no backend or database changes.

## Current app journeys

- Four primary destinations: **الحي / السوق / العقار / دليل الأعمال**.
- Public account registration, email-verification resend, OIDC Authorization Code + PKCE sign-in through the system browser, and neighborhood selection/membership using the existing APIs.
- Live neighborhood feed, Arabic post search, post creation, comments, reactions, events and RSVPs, groups and membership, and polls/voting where the backend contracts exist.
- Neighborhood classified listings are separate from the store. The property view filters real listing search by purpose/property type; the business-discovery view searches published service/listing offers and can show returned provider-rating metadata.
- Notification list/read state and the actual neighborhood membership-verification state.
- Arabic-first RTL UI with loading, empty, error, and unavailable-contract states rather than fake server data.

## Known product-contract boundaries

The current backend does **not** expose the complete public storefront/cart/order/delivery/checkout path. The سوق destination must not fabricate a catalog, stock, purchase or order confirmation. Business discovery currently searches published offers; it is not yet a complete provider-profile directory. A full inbox/conversation-list contract, device push registration/delivery, media uploads, reporting/moderation, official institution broadcasts and additional approved discovery rails remain outside this slice. Each must be connected through a verified end-to-end contract before it is presented as working.

## Security and configuration

- AppAuth handles Authorization Code + PKCE in the system browser. No confidential-client secret is packaged in the APK.
- Access and ID tokens stay in memory only; neither token is written to persistent app storage. On app launch and after an API 401, the client attempts OIDC Authorization Code + PKCE with `prompt=none` to reuse a valid system-browser session. If the provider returns `login_required`, the app returns to interactive sign-in instead of inventing a local session.
- Explicit sign-out clears local tokens and invokes AppAuth's RP-initiated end-session request with the discovered end-session endpoint, ID-token hint when available, registered post-logout redirect URI and client ID. If the provider does not confirm the end-session response, the app tells the user that only local sign-out is confirmed.
- The public client has no refresh-token grant. The resource server validates JWTs locally, so a previously issued access token may remain valid up to its documented 900-second lifetime even after server logout; the app discards its local copy immediately.
- The issuer, public client ID, API base URL and redirect URI must match the public OAuth client registered on the backend. Real staging sign-in and end-session behavior still require verification against the configured deployment. No backend or production OAuth settings are changed by this client PR.

## Build and test

Use JDK 17 and Gradle 9.6.0:

    gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest

The Android client workflow installs Android SDK platform 36, runs JVM unit tests, builds the debug APK and uploads it as a workflow artifact. The CI artifact is a debug build, not a production-signed release.

Override environment-specific values without committing them:

    gradle -p android :app:testDebugUnitTest :app:assembleDebug -PapiBaseUrl=https://YOUR-API-HOST/ -PoidcIssuer=https://YOUR-ISSUER-HOST -PoidcClientId=YOUR_PUBLIC_CLIENT_ID -PoidcRedirectUri=com.marketplace.android:/oauth2redirect

## Code boundaries

- core/network: typed DTOs and API contracts for existing backend endpoints.
- data: one repository owns HTTP and bearer-token injection.
- feature: ViewModel and StateFlow own screen state; Compose renders state and forwards actions.
- No parallel backend, local product database, sample content pretending to be server data, or client secret.

## Official references

- Android app architecture: https://developer.android.com/topic/architecture
- Jetpack Compose: https://developer.android.com/develop/ui/compose
- Android SDK setup action: https://github.com/android-actions/setup-android
- Native OAuth 2.0 guidance: https://datatracker.ietf.org/doc/html/rfc8252
- AppAuth Android: https://github.com/openid/AppAuth-Android
