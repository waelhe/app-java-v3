# ضَيف — Native Android Client

## Decision

The mobile target is a native Android application, using Kotlin and Jetpack Compose. This supersedes the proposed Flutter direction in the unmerged community execution-suite documents. It does not change the Java/Spring backend or its module boundaries.

The UI follows a single-activity, UI-state-driven structure:
- Compose screens render state and emit user actions.
- `PlatformViewModel` owns screen state and coroutines.
- `PlatformRepository` exposes backend operations to the UI.
- `PlatformApi` is the one HTTP transport and is the only layer that adds bearer tokens, applies network timeouts, and normalizes RFC 7807-style errors.
- `SecureSessionStore` encrypts AppAuth's serialized authorization state with an AES-GCM key held by Android Keystore.

## Real journeys in the first vertical slice

1. Native app launch and OAuth 2.0 Authorization Code + PKCE.
2. First-run neighborhood selection using the backend's country/governorate/city/neighborhood tree.
3. Membership creation via `PUT /api/v1/me/neighborhood`.
4. Read the authenticated member's actual neighborhood feed.
5. Open a post and read/add comments.
6. Add/remove the post's single-per-member reaction.
7. Publish a post with the backend's supported category vocabulary.
8. Request manual neighborhood verification.

The Android app consumes existing endpoints; it adds no Spring domain module, database table, or shadow source of truth.

## Verified backend contract used

| Capability | Existing endpoint |
|---|---|
| Location tree | `GET /api/v1/geo/tree` |
| My membership | `GET /api/v1/me/neighborhood` |
| Join/switch neighborhood | `PUT /api/v1/me/neighborhood` with `{ "locationId": "..." }` |
| Request manual verification | `POST /api/v1/me/neighborhood/verification-requests` |
| Read feed | `GET /api/v1/neighborhood/posts?page=0&size=20` |
| Create post | `POST /api/v1/neighborhood/posts` |
| Read/add comments | `GET/POST /api/v1/posts/{postId}/comments` |
| Add/remove reaction | `POST/DELETE /api/v1/posts/{postId}/reactions` |

The feed, comment and membership reads are authenticated and enforce neighborhood membership on the server. The client must treat 401/403/404/409/429 and server failures as normal UI states; it must not bypass those gates.

## OAuth configuration — required before login can work against production

The backend's `docs/api/public-client-auth.md` governs this flow:
- Public native client, Authorization Code + PKCE, no client secret, no API key.
- Access token lifetime is 900 seconds; backend issues no refresh token for this client.
- Authentication uses the system browser/AppAuth, never a WebView.
- The redirect URI must exactly match an entry in server-side `OAUTH_PUBLIC_CLIENT_REDIRECT_URIS`.

Build properties can be supplied through Gradle properties or environment variables:

| Build property | Environment variable | Meaning |
|---|---|---|
| `apiBaseUrl` | `DAYF_API_BASE_URL` | REST API origin |
| `authIssuer` | `AUTH_SERVER_ISSUER` | Exact OIDC issuer from deployment configuration |
| `oauthPublicClientId` | `OAUTH_PUBLIC_CLIENT_ID` | Public client id (not a secret) |
| `oauthRedirectUri` | `DAYF_OAUTH_REDIRECT_URI` | Default: `com.marketplace.dayf:/oauth2redirect` |

Before enabling production sign-in, confirm the exact deployed `AUTH_SERVER_ISSUER` and `OAUTH_PUBLIC_CLIENT_ID`, then add the exact redirect URI to the existing server configuration. Do not put any confidential client secret into the APK. CI builds the project with an empty client id, so a build does not pretend that sign-in is configured.

The first slice currently clears local app credentials on sign-out. It does **not** claim that this also terminates the authorization-server browser session; verify the deployed OIDC end-session metadata and wire that journey before advertising server-wide logout.

## Native navigation and product truth

The five primary destinations follow the owner's mobile prototype: **الحي، السوق، نشر، العقار، الأعمال**. The community path is wired to live backend APIs. The market home uses the eight product categories supplied in the product prototype, but does not render sample products, prices, ratings, stock, seller names or fake cart contents.

At the inspected backend revision, `marketplace-catalog/ProductController` exposes seller-owned product creation/read surfaces; it explicitly says public store browsing/search and seller summaries arrive in a later backend wave. Consequently, the marketplace's public product feed, offers, real cart/checkout, full property browsing and public business directory screens are not claimed as implemented by this PR. Those journeys require stable real API contracts before they can be completed without fabricating data.

## Build and verification

The CI workflow installs Gradle 8.13, JDK 17 and Android SDK 36, then runs:

```bash
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
```

The current environment used to author this change does not contain Android SDK/Gradle, so a local APK build cannot be claimed; use the Android CI result as the build gate. Opening the `android/` project in Android Studio also requires an installed compatible Gradle distribution; the Gradle Wrapper should be added when its binary can be materialized through the repository tooling.

## Next end-to-end gaps, not cosmetic tasks

1. Confirm production OAuth issuer/client id/redirect and exercise login on an Android device.
2. Generate the typed client from the live Springdoc OpenAPI document once the production/staging document is accessible; pin the generator and make regeneration a CI drift check rather than maintaining parallel API schemas.
3. Backend: public store catalog/search, category contract, offers/discounts, inventory, multi-store cart and checkout.
4. Backend: public real-estate listing browse/search and business directory browse/profile/review read contracts.
5. Backend/API: member display profiles and authorized media URLs for posts/comments, plus feed pagination/refresh and notification deep links.
6. Native: finish feature journeys on top of those real contracts, including accessibility, offline/empty/error states, media picker/upload, notification permission handling, integration/UI tests and release signing.
