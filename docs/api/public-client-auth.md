# Public Client Authentication — PKCE and Native Passkeys (L29)

## The decision

The Android/SPA client remains a public client: it never contains a client secret and receives no refresh token. The current implementation supports two user-authentication paths:

1. **Authorization Code + PKCE** at `/oauth2/authorize` for the existing OAuth/OIDC flow. Native apps use the system browser/Custom Tab per [RFC 8252](https://datatracker.ietf.org/doc/html/rfc8252), not a WebView.
2. **Native passkey assertion** through the project extension grant `urn:marketplace:params:oauth:grant-type:passkey`. Android uses Credential Manager; the server verifies the signed WebAuthn assertion through Spring Security's `spring-security-webauthn`, then Spring Security’s current Authorization Server extension points validate the grant; the native-only adapter delegates JWT encoding to Spring Security’s `JwtGenerator` using the application’s managed JWK source and claim customizer, then persists the authorization through `OAuth2AuthorizationService`. Standard authorization-code and other built-in grants continue to use Spring Security’s internally configured token generator and default customizers.

The second grant is an application-specific OAuth extension, implemented at the standard token endpoint through Spring Security 7.1.1’s documented `OAuth2TokenEndpointConfigurer` `AuthenticationConverter` and `AuthenticationProvider` extension points. It is not a new standardized OAuth grant and does not bypass the authorization server.

## Native passkey flow

1. The app calls `POST /api/v1/auth/passkeys/assertion/options`. The server generates WebAuthn request options with a random challenge, the configured RP ID and required user verification.
2. The app passes those options to Android Credential Manager's `GetPublicKeyCredentialOption` and receives the platform-generated assertion.
3. The app posts a form-encoded request to `POST /oauth2/token`:
   - `grant_type=urn:marketplace:params:oauth:grant-type:passkey`
   - `client_id=android-community-native` (the value must match `OAUTH_PUBLIC_CLIENT_ID`)
   - `challenge_id=<server-issued UUID>`
   - `credential=<Credential Manager authenticationResponseJson>`
4. Spring Security validates the WebAuthn challenge, RP ID, origin, credential and signature. The account must also remain enabled, unlocked, and unexpired. The server then creates a standard JWT access token, carrying the existing issuer, audience, subject, email and role contracts.
5. The token is a short-lived bearer token with the registered public-client lifetime (900 seconds). The native grant issues **no refresh token and no ID token**; its authorized API scope is `profile`. On expiry, the client reauthenticates with a passkey or the existing Authorization Code + PKCE fallback.

The server stores challenges in PostgreSQL, expires them after five minutes, and consumes each challenge atomically with `DELETE ... RETURNING`; expired, unknown and replayed challenges fail as `invalid_grant`. The WebAuthn credential records use Spring Security's JDBC repositories and their documented `user_entities` / `user_credentials` table contracts.

## Enrollment

Passkey enrollment is allowed only after the existing authenticated flow, for an account that is enabled and eligible to sign in:

- `POST /api/v1/auth/passkeys/registration/options` — authenticated bearer token required.
- `POST /api/v1/auth/passkeys/registration` — authenticated bearer token plus the server challenge ID and Credential Manager's registration response.

An enrollment challenge is bound to the current account and consumed once. The server does not accept credentials, usernames or roles supplied as trusted identity claims by the app; WebAuthn and the existing account store determine the identity and authorities.

## Required operator configuration — deliberately fail closed

Passkey endpoints return `503` until the relying-party configuration is present. Do not invent or use a runner-generated debug signing identity for production.

- `WEBAUTHN_RP_ID`: the host used by the Android app to reach the authorization server, without scheme or path (for the current production API host: `app-java-v3-production-59bf.up.railway.app`).
- `WEBAUTHN_RP_NAME`: human-readable relying-party name; defaults to `Marketplace`.
- `WEBAUTHN_ALLOWED_ORIGINS`: exact comma-separated WebAuthn origins, including the Android origin derived from the actual release signing certificate (Android form: `android:apk-key-hash:<base64url-certificate-hash>`).
- `ANDROID_APP_PACKAGE_NAME`: defaults to `com.marketplace.android`.
- `ANDROID_APP_SHA256_CERT_FINGERPRINTS`: comma-separated SHA-256 certificate fingerprints in the colon-delimited hexadecimal form expected by Digital Asset Links. These must derive from the same release-signing certificate as the allowed Android origin.

The server serves `/.well-known/assetlinks.json` only when the actual SHA-256 fingerprints are configured; otherwise it returns 404. The passkey APIs remain unavailable while the allowed origin is unset. This is intentional: Credential Manager's Digital Asset Links association and the server-side WebAuthn origin allowlist must agree before native sign-in is enabled. Production Railway variables have not been changed by this code change.

## Security constraints

- No direct password-login endpoint, Resource Owner Password Credentials grant, token signing code outside Spring Authorization Server, API key, or secret in the APK.
- WebAuthn validation, challenge generation, credential persistence and authenticator-counter handling use Spring Security's official implementation.
- OAuth token creation, claims customization, token lifetime, revocation persistence and issuer/audience contract remain owned by Spring Authorization Server and the existing resource-server setup.
- Only the assertion-options POST is anonymous. Credential enrollment endpoints remain authenticated. Email verification and account lifecycle gates remain authoritative.

## Official implementation references

- [RFC 8252 — OAuth 2.0 for Native Apps](https://datatracker.ietf.org/doc/html/rfc8252)
- [RFC 9700 — OAuth 2.0 Security Best Current Practice](https://datatracker.ietf.org/doc/html/rfc9700)
- [Spring Security 7.1.1 — OAuth 2.1 Authorization Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/)
- [Spring Security 7.1.1 — Protocol Endpoints and token-endpoint extension points](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/protocol-endpoints.html)
- [Spring Security 7.1.1 — `OAuth2AuthorizationServerConfigurer` API](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/config/annotation/web/configurers/oauth2/server/authorization/OAuth2AuthorizationServerConfigurer.html)
- [Spring Boot 4 — Security starter migration names](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Spring Security 7.1.1 — Passkeys](https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html)
- [Spring Boot — managed Spring Security dependency versions](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)
- [Android Developers — Sign in with a passkey](https://developer.android.com/identity/passkeys/sign-in-with-passkeys)
- [Android Developers — Create passkeys](https://developer.android.com/identity/passkeys/create-passkeys)
- [Android Identity Samples — official Credential Manager / Shrine app](https://github.com/android/identity-samples)
