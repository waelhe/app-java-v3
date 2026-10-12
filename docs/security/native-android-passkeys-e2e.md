# Native Android Passkeys — End-to-End Acceptance Runbook

This is the acceptance procedure for the Android Credential Manager client and the Spring Security 7.1.1 WebAuthn relying party. Passing JVM tests or building an APK is not equivalent to a successful passkey ceremony.

## 1. Signing identity is the source of truth

1. The current signed-release workflow is `.github/workflows/android-release.yml`. It runs only when a tag matching `android-v*` is pushed; it has no `workflow_dispatch` or `buildSignedRelease` input. It requires these repository secrets: `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and `ANDROID_RELEASE_KEY_PASSWORD`. Signed artifacts are uploaded only after `android/scripts/verify-passkey-release.sh` passes.
2. The workflow's **Derive signing identity** step emits the SHA-256 certificate fingerprint and `android:apk-key-hash:` origin in its job summary before the release preflight. That summary is identity data, not proof that a release is ready; the later gates must still pass. Do not push a production-release tag merely to discover a fingerprint. Derive it from the authorized release keystore or use the approved staging procedure.
3. If Google Play App Signing is enabled, use the **App signing key certificate** fingerprint shown in Play Console—not the upload-key fingerprint emitted by a local upload build. If the Play signing certificate is different, derive the Android origin from that Play signing certificate too.
4. Never substitute a generated/debug key or an assumed fingerprint for a release identity.

## 2. Configure the designated Railway acceptance environment

For this project, the operator explicitly designates the existing Railway `production` environment as the staging/acceptance environment. Use its current sign-in hostname `app-java-v3-production-59bf.up.railway.app`; do not create a separate Railway environment. The relying-party ID must be that bare hostname (no scheme, path, port, or trailing slash).

For temporary device acceptance, the configured signing identity must match the exact debug APK being installed. This permits validation against the designated test service but is **not** a production release identity. Before distributing a release APK/AAB, replace the debug certificate fingerprint and origin with the permanent release/App Signing certificate, then verify that Digital Asset Links and the WebAuthn origin allowlist match that release certificate.

Set the following service variables on the `app-java-v3` Railway service in the existing `production` environment. While testing a debug APK, use the fingerprint/origin derived from that exact APK; before distribution, change both to the permanent release/distribution certificate:

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

From a terminal, use the designated Railway acceptance hostname:

```bash
set -euo pipefail
HOST='app-java-v3-production-59bf.up.railway.app'
curl --fail --silent --show-error --max-redirs 0 \
  --dump-header /tmp/assetlinks.headers \
  "https://${HOST}/.well-known/assetlinks.json" \
  --output /tmp/assetlinks.json

grep -Eiq '^HTTP/[0-9.]+ 200([[:space:]]|$)' /tmp/assetlinks.headers
grep -Eiq '^content-type:[[:space:]]*application/json([;[:space:]]|$)' /tmp/assetlinks.headers
jq -e '
  type == "array" and length >= 1 and
  any(.[]; (.relation | index("delegate_permission/common.handle_all_urls")) != null and
    (.relation | index("delegate_permission/common.get_login_creds")) != null and
    .target.namespace == "android_app" and
    .target.package_name == "com.marketplace.android" and
    (.target.sha256_cert_fingerprints | length >= 1))
' /tmp/assetlinks.json
```

A redirect, non-200 response, wrong content type, package mismatch, either missing relation, or a fingerprint that does not match the exact APK under test is a failure. Android passkeys require `delegate_permission/common.handle_all_urls`; `delegate_permission/common.get_login_creds` additionally declares credential sharing. The expected path is `/.well-known/assetlinks.json`. The ephemeral CI debug certificate is permitted only for this designated acceptance run, never as the permanent release association.

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

The existing Railway `production` environment is the authorized staging/acceptance target for this task, by explicit operator instruction. Apply the passkey configuration there and run the endpoint checks and real-device acceptance sequence. The debug certificate is temporary test trust only and must be replaced by the permanent release/App Signing identity before distributing a release.
## 6. Current deployment boundary (inspection snapshot: 2026-10-12)

- The companion pull requests are still open: Android client [#548](https://github.com/waelhe/app-java-v3/pull/548) and backend [#555](https://github.com/waelhe/app-java-v3/pull/555). The backend PR must be integrated and deployed to the chosen staging environment before server passkey ceremonies can be tested.
- At initial inspection the connected Railway service had the public OAuth client configuration, but none of the WebAuthn relying-party/origin/certificate variables was present. Recheck those variables and the active deployment after configuration.
- The permanent release/distribution signing certificate is not provisioned in GitHub Actions yet; the four release keystore secrets were absent during inspection. A one-off debug identity may be used only for the exact staging APK used in acceptance, never as the permanent release identity.
- The Railway `production` environment is the designated acceptance environment. Use the exact matching debug identity only for the acceptance APK from run [38306828220](https://github.com/waelhe/app-java-v3/actions/runs/38306828220); the permanent release identity must replace it before a release is distributed.
- The passkey schema migration is named `V199_1__native_passkeys.sql` (Flyway version `199.1`), which sorts after the current `V199` baseline and before the reserved `V200`–`V208` migrations. This avoids advancing the live schema to V209 ahead of those still-open migrations.
