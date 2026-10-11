# ضَيف — Android Native

The Android client is a native Kotlin + Jetpack Compose application. It consumes the existing Spring API; it does not embed a website or expose a client secret.

## Prerequisites

- Android Studio with Android SDK Platform 36 and Build Tools 36.0.0.
- JDK 17.
- The first build bootstraps the pinned Gradle 8.13 distribution and verifies its SHA-256 checksum.

## Build and install

From this directory, build the debug APK:

    ./gradlew :app:assembleDebug

Install it on a connected Android device:

    adb install -r app/build/outputs/apk/debug/app-debug.apk

The default API URL is the staging host documented by the backend team. The OAuth client id must match the value configured by the server:

    ./gradlew :app:assembleDebug       -PbackendUrl=https://app-java-v3-staging-staging.up.railway.app       -PoauthClientId="$OAUTH_PUBLIC_CLIENT_ID"       -PoauthRedirectUri=com.marketplace.android:/oauth2/callback       -PoauthRedirectScheme=com.marketplace.android

OAUTH_PUBLIC_CLIENT_ID is a public OAuth client identifier, not a secret. Never put a client secret in the Android app.

## Required backend setting before sign-in can complete

The authorization server's public-client bootstrap reads these values from its environment:

- OAUTH_PUBLIC_CLIENT_ID
- OAUTH_PUBLIC_CLIENT_REDIRECT_URIS

Add the exact URI com.marketplace.android:/oauth2/callback to OAUTH_PUBLIC_CLIENT_REDIRECT_URIS on staging before testing browser sign-in. Preserve existing redirect URIs when updating the comma-separated value. The actual staging client id must be passed through the Gradle property; the local fallback marketplace-public-client is a development default, not a statement about the live staging value.

The server enforces Authorization Code + PKCE for native public clients and does not issue refresh tokens. The app stores only the short-lived access token, encrypted with Android Keystore AES/GCM. When the token expires, the user signs in again through the authorization server.

## Implemented real journeys

1. Account registration through POST /api/v1/auth/register; sign-in through the server's OIDC discovery and AppAuth Authorization Code + PKCE flow.
2. Neighborhood selection from live GET /api/v1/geo/suggest, filtering to level-3 neighborhood nodes, and membership through PUT /api/v1/me/neighborhood.
3. Live, membership-scoped community feed and Arabic search.
4. Publish a post, optionally attach a photo using the server's presigned-upload → object-store PUT → completion-verification sequence.
5. Open post details, read/add comments, and add/remove the one-per-member reaction.
6. Membership status, change/leave neighborhood, session expiry handling, loading/empty/error states, RTL layout and the native Android photo picker.

This is the first end-to-end native community slice, not a claim that every marketplace/business/messaging feature is already present in the mobile app. Further domains should be added as complete user journeys against their actual backend contracts, not as mock screens.
