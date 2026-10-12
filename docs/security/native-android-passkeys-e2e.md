# Native Android Passkeys — End-to-End Acceptance Runbook

This is the acceptance procedure for the Android Credential Manager client and the Spring Security 7.1.1 WebAuthn relying party. Passing JVM tests or building an APK is not equivalent to a successful passkey ceremony.

## 1. Signing identity is the source of truth

1. Build an opt-in release-signed APK/AAB through the **Android client** GitHub Actions workflow with `buildSignedRelease=true`. The workflow requires these repository secrets: `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and `ANDROID_RELEASE_KEY_PASSWORD`.
2. Use the certificate fingerprint and `android:apk-key-hash:` origin emitted in the workflow summary only when the same release keystore is the certificate installed on users' devices.
3. If Google Play App Signing is enabled, use the **App signing key certificate** fingerprint shown in Play Console—not the upload-key fingerprint emitted by a local upload build. If the Play signing certificate is different, derive the Android origin from that Play signing certificate too.
4. Never substitute a generated/debug key or an assumed fingerprint for a release identity.

## 2. Configure a non-production relying party first

Use a dedicated Railway staging environment and its own sign-in hostname. The relying-party ID must be the bare hostname (no scheme, path, port, or trailing slash). For the production hostname currently used by the app, the host is `app-java-v3-production-59bf.up.railway.app`; do not turn Passkeys on there before the staging acceptance gate passes.

Set the following service variables in the target environment, using the actual release/distribution certificate and matching hostname:

| Variable | Required value |
| --- | --- |
| `OAUTH_PUBLIC_CLIENT_ID` | `android-community-native` |
| `OAUTH_PUBLIC_CLIENT_REDIRECT_URIS` | Exact registered URI `com.marketplace.android:/oauth2redirect` |
| `WEBAUTHN_RP_ID` | Bare sign-in hostname |
| `WEBAUTHN_RP_NAME` | `Marketplace` (or the approved relying-party display name) |
| `WEBAUTHN_ALLOWED_ORIGINS` | Exact derived origin `android:apk-key-hash:<base64url-sha256-of-certificate-DER>` |
| `ANDROID_APP_PACKAGE_NAME` | `com.marketplace.android` |
| `ANDROID_APP_SHA256_CERT_FINGERPRINTS` | Actual certificate SHA-256 fingerprint, colon-separated |

The backend is deliberately fail-closed: the origin allowlist must exactly match the origin derived from the configured certificate fingerprints. Do not place client secrets in the Android app.

## 3. Verify Digital Asset Links over HTTPS

From a terminal, replace `HOST` with the staging sign-in hostname:

```bash
set -euo pipefail
HOST='staging-host.example'
curl --fail --silent --show-error --max-redirs 0 \
  --dump-header /tmp/assetlinks.headers \
  "https://${HOST}/.well-known/assetlinks.json" \
  --output /tmp/assetlinks.json

grep -Eiq '^HTTP/[0-9.]+ 200([[:space:]]|$)' /tmp/assetlinks.headers
grep -Eiq '^content-type:[[:space:]]*application/json([;[:space:]]|$)' /tmp/assetlinks.headers
jq -e '
  type == "array" and length >= 1 and
  any(.[]; (.relation | index("delegate_permission/common.get_login_creds")) != null and
    .target.namespace == "android_app" and
    .target.package_name == "com.marketplace.android" and
    (.target.sha256_cert_fingerprints | length >= 1))
' /tmp/assetlinks.json
```

A redirect, non-200 response, wrong content type, package mismatch, or missing real certificate fingerprint is a failure. The expected path is `/.well-known/assetlinks.json`; no debug certificate should be published as a production association.

## 4. Real-device acceptance sequence

Run this on a supported Android device (Android 9 / API 28 or newer) with an available credential provider and the actual release/distribution-signed APK installed:

1. Create an account and complete email verification. Sign in using the explicit Authorization Code + PKCE fallback.
2. Open the account/profile area and register a passkey. Complete the system Credential Manager prompt; verify the request came from the configured relying-party host and that the server stored a credential for the authenticated principal.
3. Sign out, return to the unauthenticated screen, choose **المتابعة بمفتاح المرور**, and select the newly registered passkey. Verify the token endpoint returns an access token, not a refresh token or ID token.
4. Verify the app then calls the protected `GET /api/v1/me/neighborhood` endpoint using `Authorization: Bearer <access-token>`. The success criterion is an authenticated response (normally HTTP 200 for an eligible account), not a fabricated local session. No token or Authorization header may appear in logs.
5. Confirm a replayed, expired, wrong-purpose, or principal-mismatched challenge is rejected, and that a protected API request without a valid access token is rejected.
6. Test cancellation and the no-passkey case: the UI must remain usable and the PKCE fallback must remain available.

## 5. CI versus real E2E

The automated Android workflow runs JVM tests, builds debug artifacts, and runs an emulator instrumentation test that checks the actual activity exposes both the passkey and PKCE entry points. That emulator UI test does **not** create a real passkey. The backend suite covers challenge lifecycle, client policy, configuration fail-closed behavior, and invalid assertions, but an invalid assertion test is not a positive WebAuthn ceremony.

Do not mark this feature E2E-complete until all of the following are recorded against the same release identity and staging deployment:

- Android JVM tests and emulator instrumentation tests pass on the latest commit.
- Backend CI, full integration tests, CodeQL, and container scan pass on the latest commit.
- Digital Asset Links returns valid JSON over HTTPS and matches the actual installed signing certificate.
- A human completes passkey registration and subsequent sign-in on a physical Android device.
- The app obtains a server-issued access token and calls a protected API successfully.

No deployment to production is authorized by this runbook. Promote the configuration only after staging acceptance and explicit deployment approval.
