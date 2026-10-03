# Mobile refresh grant (public client) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the native mobile app long-lived sessions by registering the `refresh_token` grant on the public `marketplace-mobile` client with rotation and a 90-day sliding TTL, so an active user is never logged out.

**Architecture:** Two-line registration change plus settings in `OAuth2PublicClientInitializer` (the single official mutation path, `RegisteredClientRepository.save`). No framework customization: the default `PublicClientAuthenticationConverter/Provider`, the code-exchange issuance gate (`grants.contains(REFRESH_TOKEN)`), and the refresh provider all support `NONE`-method clients out of the box (verified in Security 7.1.x sources: code provider :237 gates on grant registration only, refresh provider :131/:170-172 has no `NONE` block). Converge-on-boot updates the production row on the next deploy; nothing is edited by hand in the database.

**Tech Stack:** Java 25 · Spring Security 7.1.1 `spring-security-oauth2-authorization-server` module (Boot 4.1.1-managed; the standalone 1.5.x line is retired to spring-attic and is NOT used here) · JUnit 5 + Mockito + AssertJ · Maven (`.\mvnw.cmd`, PowerShell)

**Spec:** Verified decision record, all on the shipped line — (1) Security 7.1.1 reference, Authorization Server → Client Authentication: `none (public clients)` is a supported method with default `PublicClientAuthenticationConverter/Provider`; the feature list carries the Refresh Token grant; (2) 7.1.x `OAuth2AuthorizationCodeAuthenticationProvider:237` gates refresh issuance solely on grant registration, with the team's posture expressed as comment `:236` ("Do not issue refresh token to public client") and NO client-method check — posture, not enforcement; (3) 7.1.x `OAuth2RefreshTokenAuthenticationProvider:131` accepts refresh on grant registration with no `NONE` block, and `:170-172` explicitly handles public clients (DPoP binding check); (4) RFC 9700 §2.2.2 permits public-client refresh under the rotation branch (Keycloak/Auth0/AppAuth practice); (5) RFC 8252 §6/§8.1/§8.4/§8.5 require PKCE + exact redirect match + no shared secret (all already true: `requireProofKey`, exact-match validator, `NONE`); (6) live DB row `marketplace-mobile` is `none` / `authorization_code`-only with redirect `com.marketplace.app:/oauth2/callback`; (7) consumer doc `docs/mobile-app-integration.md` §2/§4/§7 (updated in Task 5).

## Global Constraints

- Public client stays secret-less (`ClientAuthenticationMethod.NONE`) with `requireProofKey(true)` — RFC 9700 §2.1.1, non-negotiable.
- Rotation stays ON (`reuseRefreshTokens(false)`) — the RFC 9700 rotation branch that makes device-held refresh acceptable.
- No custom `AuthenticationProvider`, `AuthenticationConverter`, or token-endpoint customizer — defaults only.
- No hand SQL against `oauth2_registered_client` — converge-on-boot owns the row.
- Exact values: access 900s (unchanged), code 300s (unchanged), refresh `Duration.ofDays(90)`, rotation off-reuse (`false`).
- Grant insertion order is `AUTHORIZATION_CODE` then `REFRESH_TOKEN` (tests assert `containsExactly` in this order; `RegisteredClient` preserves insertion order).

---

## File Structure

- Modify: `marketplace-platform-infra/src/main/java/com/marketplace/shared/security/OAuth2PublicClientInitializer.java` — grant registration, token settings, class/method javadoc (the "no refresh on purpose" paragraphs are rewritten to the rotation decision with its basis).
- Modify: `marketplace-platform-infra/src/test/java/com/marketplace/shared/security/OAuth2PublicClientInitializerTest.java` — fixture + rogue-row assertion + one new explicit contract test.
- Modify: `docs/mobile-app-integration.md` — §2 step 5, §4, §7 (refresh now exists; Authenticator pattern applies with single-flight; backend item closed).
- Touch nothing else. In particular: no `SecurityConfig` change, no migration, no `application.yml` change, no new dependency.

---

### Task 1: Public-client fixture and rogue-row test expect refresh (RED)

**Files:**
- Modify: `marketplace-platform-infra/src/test/java/com/marketplace/shared/security/OAuth2PublicClientInitializerTest.java:146-149` (grant assertion)
- Modify: same file `publicClientRow` builder (grant + token settings)
- Test: same file (new test `refreshIsLongLivedRotatingNinetyDaySlidingWindow`)

**Interfaces:**
- Consumes: existing helpers `publicClientRow`, `savedClientArgument`, `properties`, `REDIRECT` (unchanged).
- Produces: failing tests proving the derived definition lacks the refresh contract.

- [ ] **Step 1: Extend the fixture to the target definition**

```java
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(redirectUri)
```

```java
        return builder.tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofSeconds(900))
                        .authorizationCodeTimeToLive(Duration.ofSeconds(300))
                        .refreshTokenTimeToLive(Duration.ofDays(90))
                        .reuseRefreshTokens(false)
                        .build())
                .build();
```

- [ ] **Step 2: Extend the rogue-row assertion to the target definition**

```java
        assertThat(saved.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE,
                        AuthorizationGrantType.REFRESH_TOKEN);
```

- [ ] **Step 3: Add the explicit refresh-contract test**

```java
    @Test
    void refreshIsLongLivedRotatingNinetyDaySlidingWindow() {
        when(repository.findByClientId("mobile")).thenReturn(null);

        new OAuth2PublicClientInitializer(properties("mobile", REDIRECT), repository, environment(false)).run(null);

        RegisteredClient saved = savedClientArgument();
        assertThat(saved.getAuthorizationGrantTypes())
                .as("mobile stays logged in: refresh grant present")
                .contains(AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(saved.getTokenSettings().getRefreshTokenTimeToLive())
                .as("90-day sliding window: any use within 90 days extends another 90")
                .isEqualTo(Duration.ofDays(90));
        assertThat(saved.getTokenSettings().isReuseRefreshTokens())
                .as("rotation on: a stolen refresh is single-use; legitimate use kills it")
                .isFalse();
    }
```

- [ ] **Step 4: Run the test class and verify RED**

Run: `.\mvnw.cmd -pl marketplace-platform-infra -am test "-Dtest=OAuth2PublicClientInitializerTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Djacoco.skip=true"`
Expected: FAIL — `doesNothingWhenStoredDefinitionAlreadyMatches` (stored fixture now differs from derived definition), `convergesForeignRowWithSecretBackToSecretlessDefinition` (grant assertion), and the new test (grant absent). Failures must name the refresh grant/TTL, not compilation errors.

- [ ] **Step 5: Commit the RED test alone**

```bash
git add marketplace-platform-infra/src/test/java/com/marketplace/shared/security/OAuth2PublicClientInitializerTest.java
git commit -m "test: public client expects rotating 90-day refresh (RED)"
```

### Task 2: Register the grant and settings in the initializer (GREEN)

**Files:**
- Modify: `marketplace-platform-infra/src/main/java/com/marketplace/shared/security/OAuth2PublicClientInitializer.java:39-44` (class-javadoc refresh paragraph)
- Modify: same file `buildTarget` grant line (~:158)
- Modify: same file `buildTokenSettings` (~:180-190) + its javadoc

**Interfaces:**
- Consumes: `AuthorizationGrantType.REFRESH_TOKEN`, `Duration`, `TokenSettings` (all already imported).
- Produces: derived definition `{AUTHORIZATION_CODE, REFRESH_TOKEN}` + TTLs `{900, 300, 90d}` + `reuse=false`, converged to the row on next boot via the unchanged `needsSave` comparator (it already compares grant types and settings maps).

- [ ] **Step 1: Add the grant to the derived definition**

```java
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
```

- [ ] **Step 2: Add refresh TTL + rotation to token settings**

```java
    private static TokenSettings buildTokenSettings() {
        return TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofSeconds(900))
                .authorizationCodeTimeToLive(Duration.ofSeconds(300))
                .refreshTokenTimeToLive(Duration.ofDays(90))
                .reuseRefreshTokens(false)
                .build();
    }
```

- [ ] **Step 3: Rewrite the two javadoc paragraphs that say "no refresh on purpose"**

Replace the `<li>{@code authorization_code} grant only ...` item (lines 39-44) with: the client carries `authorization_code` + `refresh_token`; refresh on a public client follows the RFC 9700 §2.2.2 rotation branch (each use consumes and reissues; a stolen refresh is single-use; legitimate use invalidates the stolen one); 90-day sliding TTL means any use within 90 days extends another 90, so an active install never logs out; the SAS how-to BFF recommendation is recorded as the consciously declined alternative with its reason (native app cannot hold a BFF cookie across Custom-Tab/OkHttp boundary).

Replace the `buildTokenSettings` javadoc ("No refresh-token settings on purpose...") with: refresh TTL 90 days + `reuseRefreshTokens(false)`; rotation is the mitigation that makes device-held refresh acceptable; access stays 900s so a leaked access token is short-lived.

- [ ] **Step 4: Run the test class and verify GREEN**

Run: same command as Task 1 Step 4.
Expected: PASS — all tests in `OAuth2PublicClientInitializerTest` green, output pristine (no errors/warnings).

- [ ] **Step 5: Run the whole module test suite (no regressions)**

Run: `.\mvnw.cmd -pl marketplace-platform-infra -am test "-Djacoco.skip=true"`
Expected: BUILD SUCCESS, zero failures/errors/skips attributable to this change.

- [ ] **Step 6: Commit the implementation**

```bash
git add marketplace-platform-infra/src/main/java/com/marketplace/shared/security/OAuth2PublicClientInitializer.java
git commit -m "feat(auth): rotating 90-day refresh for the public mobile client"
```

### Task 3: Check no other test pins the old definition

**Files:** none (read-only verification; edits only if a pin is found).

- [ ] **Step 1: Search for competing pins**

Run: `Select-String -Path "marketplace-*\src\test" -Pattern 'marketplace-mobile|REFRESH_TOKEN'`
Expected: only `OAuth2PublicClientInitializerTest` references the mobile grants. If another test pins `authorization_code`-only for the public client, update it to the Task 1 definition in the same way (fixture + assertion) and re-run it — do not weaken it.

- [ ] **Step 2: Search governance guards for grant allow-lists**

Run: `Select-String -Path "marketplace-app\src\test" -Pattern 'REFRESH_TOKEN|allowedGrants|grantTypes'`
Expected: no guard forbids the refresh grant on the public client. If one exists, stop and escalate to the user — do not edit guards silently.

### Task 4: Update the mobile handoff doc

**Files:**
- Modify: `docs/mobile-app-integration.md` (§2 step 5, §4, §7)

- [ ] **Step 1: Rewrite §2 step 5 (token response now includes refresh)**

Replace "There is no refresh token" with: response contains `access_token` (15 min) + `id_token` + `refresh_token` (90-day sliding, rotating: each use returns a new one and kills the old). Store the refresh in platform secure storage (Android Keystore-backed EncryptedSharedPreferences). On expiry/access-401, `POST {base}/oauth2/token` with `grant_type=refresh_token`, `refresh_token`, `client_id=marketplace-mobile` — no secret, no verifier.

- [ ] **Step 2: Rewrite §4 (Authenticator now applies)**

Replace "There is currently no token refresh to automate" with: single-flight refresh via `Mutex` — one coroutine refreshes while the rest wait and retry with the new access token (rotation punishes parallel refreshes: the loser's token is dead by design).

- [ ] **Step 3: Close §7 backend item**

Replace the refresh-grant proposal with: done — grant registered, 90-day rotating refresh live after next production deploy; the app must still re-login via browser only if unused for 90 days or on explicit logout/revocation.

- [ ] **Step 4: Commit the doc**

```bash
git add docs/mobile-app-integration.md
git commit -m "docs: mobile refresh grant live — handoff updated"
```

### Task 5: Ship (PR + gates + live proof)

**Files:** none (process only).

- [ ] **Step 1: Push and open the PR**

```bash
git push -u origin feat/mobile-refresh-grant
```

```bash
gh pr create --base main --head feat/mobile-refresh-grant --title "feat(auth): rotating 90-day refresh for the public mobile client" --body "Public client keeps NONE + PKCE (RFC 9700 2.1.1). Adds REFRESH_TOKEN grant with 90-day sliding TTL and rotation (reuse=false) — the RFC 9700 2.2.2 rotation branch Keycloak/Auth0/AppAuth follow; Security 7.1.x sources enforce no NONE-block (issuance gated only on grant registration). Converge-on-boot updates the prod row on deploy; no hand SQL, no SecurityConfig change, no migration. Tests: OAuth2PublicClientInitializerTest extended (RED then GREEN). Merge by user word only."
```

- [ ] **Step 2: Trigger CodeRabbit and wait for CI**

```bash
gh pr comment <N> --body "@coderabbitai review"
```

Expected: Build & Test, Full Integration Test, CodeQL, Trivy, Container Scan green; CodeRabbit findings resolved. Do NOT merge — merge is the user's word only (§14.3).

- [ ] **Step 3: After merge + production deploy, verify the row (read-only)**

```sql
SELECT client_id, authorization_grant_types FROM public.oauth2_registered_client WHERE client_id = 'marketplace-mobile';
```

Expected: `authorization_code,refresh_token` on the production branch.

- [ ] **Step 4: Verify issuance end-to-end (needs one real mobile login)**

Via the app: log in, capture that a `refresh_token` is returned; wait past access expiry (or revoke the access token via `/oauth2/revoke`), call the refresh grant, expect a new access+refresh pair and the old refresh rejected (`invalid_grant`) — proving rotation. Record the evidence; close the loop in chat.
