# ضَيف — Native Android client

This is the native Android application for the community platform. It uses Kotlin, Jetpack Compose, Retrofit/Moshi and AppAuth, and remains outside the root Maven reactor. It adds no Spring module and changes no backend code.

## First working vertical slice

- OpenID Connect Authorization Code + PKCE through the system browser.
- Neighborhood lookup and membership creation through the existing geo and membership APIs.
- Real neighborhood feed, Arabic post search, publishing posts, comments and reactions.
- Real neighborhood market, publishing and withdrawing the caller's own items.
- In-app notifications and mark-as-read.
- Profile with the actual neighborhood-membership verification state.
- Arabic-first RTL UI, and explicit loading, empty, error, expired-session and offline states.

Server data only: failed/unavailable API calls are error states, not sample content. The access token is kept in memory only. The current public-client registration issues no refresh tokens; HTTP 401 clears the token and takes the user to sign-in.

## Build

Use Android Studio with JDK 17 and Gradle 9.6.0, or install Gradle 9.6.0 locally:

    gradle -p android :app:testDebugUnitTest :app:assembleDebug

The default endpoint is the shared staging backend; production is never the default. Override configuration without committing environment-specific values:

    gradle -p android :app:testDebugUnitTest :app:assembleDebug -PapiBaseUrl=https://YOUR-API-HOST/ -PoidcIssuer=https://YOUR-ISSUER-HOST -PoidcClientId=YOUR_PUBLIC_CLIENT_ID -PoidcRedirectUri=com.marketplace.android:/oauth2redirect

The issuer, public client ID and redirect URI must match the public OAuth client registered on the backend. **Do not bundle a client secret.** The backend's existing OAuth2PublicClientInitializer reads OAUTH_PUBLIC_CLIENT_ID and OAUTH_PUBLIC_CLIENT_REDIRECT_URIS from server configuration. If the public client is not provisioned with this exact redirect URI, sign-in will fail; no fake login is provided.

## Architecture

- core/network: typed API contracts matching existing Spring endpoints.
- data: one repository owns HTTP and bearer-token injection.
- feature: ViewModel/state flow owns screen state; Compose renders state and forwards actions.
- No database, fake backend, parallel business rules, or token persistence is introduced.

## Current scope boundaries

The backend does not expose a dedicated community conversation-inbox listing contract in this slice, so this initial app does not pretend to implement a complete inbox; that contract must be agreed before messaging UI is connected. The first slice also does not yet cover media upload, push-token registration/delivery, moderation/report flows, events/institutions/business directory, or the topical discovery rails described in the community product plan. These are product capabilities to implement as complete vertical slices, not static screens.

## Official references

- Android app architecture: https://developer.android.com/topic/architecture
- Jetpack Compose: https://developer.android.com/develop/ui/compose
- AGP 9 built-in Kotlin: https://developer.android.com/build/migrate-to-built-in-kotlin
- AGP 9.4 compatibility: https://developer.android.com/build/releases/agp-9-4-0-release-notes
- Native OAuth 2.0 guidance: https://datatracker.ietf.org/doc/html/rfc8252
- AppAuth Android: https://github.com/openid/AppAuth-Android
