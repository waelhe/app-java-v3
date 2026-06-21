# External Services Setup Guide

This document explains how to configure every external service that the Marketplace
backend depends on. Each section cites the **official documentation** verbatim and
maps the setup steps to the exact configuration keys used in `application.yml`.

> **Audience**: developers onboarding to the project, operators deploying to a new
> environment, and anyone who needs to rotate a credential.
>
> **Verification rule**: every environment variable listed here is referenced from
> `application.yml` / `application-prod.yml` via the `${VAR_NAME}` placeholder
> syntax. If a variable is missing, the application will not start in `prod`
> profile (it uses `${VAR_NAME}` without a default — fail-fast).

---

## Table of Contents

1. [PostgreSQL (primary database)](#1-postgresql-primary-database)
2. [Redis (cache + sessions + rate limiting)](#2-redis-cache--sessions--rate-limiting)
3. [SMTP (transactional email)](#3-smtp-transactional-email)
4. [GitHub OAuth2 (social login)](#4-github-oauth2-social-login)
5. [Google OAuth2 (social login)](#5-google-oauth2-social-login)
6. [JWT signing keystore](#6-jwt-signing-keystore)
7. [Environment variable reference](#7-environment-variable-reference)

---

## 1. PostgreSQL (primary database)

**Used for**: all persistent data (users, bookings, payments, audit logs, etc.)
**Driver**: `org.postgresql.Driver` via `spring-boot-starter-data-jpa`
**Migrations**: Flyway (`org.flywaydb:flyway-database-postgresql`)

### 1.1 Install PostgreSQL 17

```bash
# Debian/Ubuntu
sudo apt-get install -y postgresql-17

# macOS (Homebrew)
brew install postgresql@17

# Docker (quickest for local dev)
docker run -d --name marketplace-pg \
  -e POSTGRES_DB=marketplace \
  -e POSTGRES_USER=marketplace \
  -e POSTGRES_PASSWORD=change_me \
  -p 5432:5432 \
  postgres:17-alpine
```

### 1.2 Create the database and user

```bash
sudo -u postgres psql <<'SQL'
CREATE DATABASE marketplace;
CREATE USER marketplace WITH ENCRYPTED PASSWORD 'change_me';
GRANT ALL PRIVILEGES ON DATABASE marketplace TO marketplace;
SQL
```

### 1.3 Configure the application

The project reads the JDBC URL from the `DB_URL` environment variable
(see `application.yml` → `spring.datasource.url`):

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/marketplace}
    username: ${DB_USERNAME:marketplace}
    password: ${DB_PASSWORD:change_me}
    hikari:
      maximum-pool-size: 10
```

### 1.4 How Flyway auto-configuration works

> **Official doc** — <https://docs.spring.io/spring-boot/how-to/data-initialization.html>:
>
> "To automatically run Flyway database migrations on startup, add the appropriate
> Flyway module to your classpath. … use `org.flywaydb:flyway-database-postgresql`
> with PostgreSQL."
>
> "Typically, migrations are scripts in the form `V<VERSION>__<NAME>.sql` … By
> default, they are in a directory called `classpath:db/migration`."
>
> "Spring Boot calls `Flyway.migrate()` to perform the database migration."

**Project mapping**: migration scripts live in
`marketplace-*/src/main/resources/db/migration/` and follow the
`V<VERSION>__<NAME>.sql` convention. No manual `migrate` command is needed —
Spring Boot runs Flyway automatically on application startup.

### 1.5 Connection troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection refused` | PostgreSQL not running, or wrong host/port | `pg_isready -h localhost -p 5432` |
| `FATAL: password authentication failed` | Wrong `DB_PASSWORD` | Verify with `psql -U marketplace -d marketplace -h localhost` |
| `database "marketplace" does not exist` | DB not created | Run the SQL block in §1.2 |
| `Flyway: Validate failed: Migration checksum mismatch` | A committed migration was edited after being applied | Never edit applied migrations — write a new `V<N+1>__fix.sql` instead |

**Reference**: <https://docs.spring.io/spring-boot/how-to/data-initialization.html>

---

## 2. Redis (cache + sessions + rate limiting)

**Used for**:
- Spring Cache (`@Cacheable`) — catalog, pricing lookups
- Spring Session — HTTP session store (replaces in-memory sessions)
- Distributed rate limiting — brute-force protection, TOTP replay protection
- OAuth2 authorization store — JWT revocation registry

**Client**: Lettuce (default in `spring-boot-starter-data-redis`)

### 2.1 Install Redis 7

```bash
# Debian/Ubuntu
sudo apt-get install -y redis-server

# macOS
brew install redis

# Docker
docker run -d --name marketplace-redis \
  -p 6379:6379 \
  redis:7-alpine \
  redis-server --requirepass change_me
```

### 2.2 Configure the application

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
  cache:
    type: ${CACHE_TYPE:redis}
    redis:
      time-to-live: 30m
```

### 2.3 What the official doc says

> **Official doc** — <https://docs.spring.io/spring-boot/reference/data/nosql.html#data.nosql.redis>:
>
> "By default, the instance tries to connect to a Redis server at `localhost:6379`.
> You can specify custom connection details using `spring.data.redis.*` properties."
>
> "When setting the `url`, the host, port, username and password properties are
> ignored."
>
> "By default, a pooled connection factory is auto-configured if `commons-pool2`
> is on the classpath."

**Project mapping**: the project sets `spring.data.redis.host/port/password` from
env vars and uses `spring.cache.type=redis` + `spring.cache.redis.time-to-live`
for cache TTL. ✅ matches the documented property namespace.

### 2.4 Security note — rate limiter fail-open policy

The `DistributedRateLimiter` is configured via
`marketplace.security.rate-limiter.fail-open` (default: `false` = fail-closed
per OWASP "Fail Securely"). When Redis is unavailable:
- `fail-open=false` → authentication requests are **denied** (safer)
- `fail-open=true` → authentication requests are **allowed** (higher availability,
  lower security)

**Reference**: <https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#account-lockout>

---

## 3. SMTP (transactional email)

**Used for**: welcome emails, password reset, booking confirmations, dispute
notifications.
**Conditionally activated**: the `EmailService` bean is only created when a
`JavaMailSender` bean exists (i.e. when `spring.mail.host` is set). This is the
documented `@ConditionalOnBean` pattern.

### 3.1 Choose an SMTP provider

| Provider | Host | Port | Notes |
|----------|------|------|-------|
| **MailHog** (dev) | `localhost` | `1025` | No auth, no TLS — local dev only |
| **Mailtrap** (staging) | `sandbox.smtp.mailtrap.io` | `2525` | Inbox isolation, fake delivery |
| **Amazon SES** (prod) | `email-smtp.<region>.amazonaws.com` | `587` | STARTTLS, IAM SMTP credentials |
| **SendGrid** (prod) | `smtp.sendgrid.net` | `587` | API key as password (`apikey`) |
| **Gmail** (testing only) | `smtp.gmail.com` | `587` | App Password required; rate-limited |

### 3.2 Configure the application

```yaml
spring:
  mail:
    host: ${MAIL_HOST:localhost}
    port: ${MAIL_PORT:1025}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    properties:
      "[mail.smtp.auth]": true
      "[mail.smtp.starttls.enable]": true
      "[mail.smtp.connectiontimeout]": 5000
      "[mail.smtp.timeout]": 3000
      "[mail.smtp.writetimeout]": 5000
```

### 3.3 What the official doc says

> **Official doc** — <https://docs.spring.io/spring-boot/reference/io/email.html>:
>
> "If `spring.mail.host` and the relevant libraries (as defined by
> `spring-boot-starter-mail`) are available, a default `JavaMailSender` is
> created if none exists."
>
> "In particular, certain default timeout values are infinite, and you may want
> to change that to avoid having a thread blocked by an unresponsive mail
> server":
>
> ```properties
> spring.mail.properties[mail.smtp.connectiontimeout]=5000
> spring.mail.properties[mail.smtp.timeout]=3000
> spring.mail.properties[mail.smtp.writetimeout]=5000
> ```

**Project mapping**: `application-prod.yml` uses the **exact** `5000/3000/5000ms`
timeouts from the reference. ✅

### 3.4 Local development with MailHog

```bash
# Start MailHog (Docker)
docker run -d --name mailhog -p 1025:1025 -p 8025:8025 mailhog/mailhog

# Set env vars (application-dev.yml already defaults to these)
export MAIL_HOST=localhost
export MAIL_PORT=1025
# MAIL_USERNAME and MAIL_PASSWORD can be empty for MailHog
```

Open <http://localhost:8025> to view captured emails.

### 3.5 Production checklist

- [ ] `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` set in the
      environment (never in git)
- [ ] `mail.smtp.auth=true` and `mail.smtp.starttls.enable=true` in
      `application-prod.yml`
- [ ] Sender domain has SPF, DKIM, and DMARC DNS records (provider-specific)
- [ ] Provider's sending quota is sufficient for expected volume
- [ ] `spring.mail.test-connection=false` (default) — leave it off in prod to
      avoid failing startup on a transient SMTP outage

**Reference**: <https://docs.spring.io/spring-boot/reference/io/email.html>

---

## 4. GitHub OAuth2 (social login)

**Used for**: "Sign in with GitHub" button. Lets users authenticate using their
GitHub account instead of a local password.
**Scopes used**: `user:email,read:user` (minimal, read-only)

### 4.1 Create a GitHub OAuth App

> **Official doc** — <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app>:
>
> 1. "In the upper-right corner of any page on GitHub, click your profile picture,
>    then click **Settings**."
> 2. "In the left sidebar, click **Developer settings**."
> 3. "In the left sidebar, click **OAuth apps**."
> 4. "Click **New OAuth App**."
> 5. "In **Application name**, type the name of your app."
> 6. "In **Homepage URL**, type the full URL to your app's website."
> 7. "Optionally, in **Application description**, type a description."
> 8. "In **Authorization callback URL**, type the callback URL of your app."
> 9. "Click **Register application**."

### 4.2 Set the Authorization callback URL

The callback URL must be exactly:

```
https://<your-api-host>/login/oauth2/code/github
```

> **Official doc** — <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps>:
>
> "The redirect URL's host (excluding sub-domains) and port must exactly match the
> callback URL. The redirect URL's path must reference a subdirectory of the
> callback URL."
>
> "For the `http://127.0.0.1/path` callback URL, you can use this redirect_uri if
> your application is listening on port 1234: `http://127.0.0.1:1234/path`."

**Examples**:

| Environment | Callback URL |
|-------------|--------------|
| Local dev | `http://127.0.0.1:8080/login/oauth2/code/github` |
| Staging | `https://staging-api.marketplace.com/login/oauth2/code/github` |
| Production | `https://api.marketplace.com/login/oauth2/code/github` |

> ⚠️ "OAuth apps cannot have multiple callback URLs, unlike GitHub Apps." —
> Create a separate OAuth App per environment.

### 4.3 Copy the Client ID and generate a Client Secret

After registering the app:
1. Copy the **Client ID** (public identifier).
2. Click **Generate a new client secret** → copy it immediately (it's only
   shown once).
3. Store both as environment variables:

```bash
export GITHUB_CLIENT_ID=Iv1.abc123def456
export GITHUB_CLIENT_SECRET=secret_abc123def456ghi789
```

### 4.4 Configure the application

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          github:
            client-id: ${GITHUB_CLIENT_ID:}
            client-secret: ${GITHUB_CLIENT_SECRET:}
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: user:email,read:user
```

> The `{baseUrl}` and `{registrationId}` placeholders are resolved by Spring
> Security at runtime — do **not** replace them with literal values. Spring
> expands `{baseUrl}/login/oauth2/code/github` to the full URL matching your
> deployment host.

### 4.5 Why these scopes?

> **Official doc** — <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps>:
>
> - `read:user` — "Grants access to read a user's profile data."
> - `user:email` — "Grants read access to a user's email addresses."

These are the minimal read-only scopes needed for social login. The project
does **not** request `repo`, `write:org`, or any write scope — least-privilege
principle.

**Reference**: <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app>

---

## 5. Google OAuth2 (social login)

**Used for**: "Sign in with Google" button. Lets users authenticate using their
Google account via OpenID Connect.
**Scopes used**: `openid,profile,email`

### 5.1 Create a Google Cloud project (if you don't have one)

1. Go to <https://console.cloud.google.com/>.
2. Click the project selector → **New Project** → name it → **Create**.

### 5.2 Configure the OAuth consent screen

1. Go to **APIs & Services → OAuth consent screen**.
2. Choose **User type**: External (unless you're a Google Workspace org).
3. Fill in: App name, support email, developer email.
4. Add **Scopes**: `openid`, `../auth/userinfo.email`, `../auth/userinfo.profile`.
5. Add **Test users** (your email) if in Testing mode.
6. Click **Save and Continue** through all steps.

> ⚠️ The consent screen must be published (or you must be a test user) before
> login works. Publishing to "In production" requires Google verification if you
> request sensitive scopes — `openid/profile/email` are non-sensitive.

### 5.3 Create the OAuth 2.0 Client ID

> **Official doc** — <https://support.google.com/cloud/answer/6158849>:
>
> 1. "Navigate to the Google Auth Platform Clients page."
> 2. "Click **CREATE CLIENT**."
> 3. "Select the appropriate application type for your application" — choose
>    **Web application**.
> 4. "Fill out the required information for the select client type and click the
>    **CREATE** button to create the client."

### 5.4 Set the Authorized redirect URIs

> **Official doc** — <https://support.google.com/cloud/answer/6158849>:
>
> "Redirect URIs must use the **HTTPS** scheme, not plain HTTP. Localhost URIs
> (including localhost IP address URIs) are exempt from this rule."
>
> "If the redirect_uri passed in the authorization request does not match an
> authorized redirect URI for the OAuth client ID, you will receive a
> `redirect_uri_mismatch` error."

The redirect URI to register is:

```
https://<your-api-host>/login/oauth2/code/google
```

**Examples**:

| Environment | Redirect URI |
|-------------|--------------|
| Local dev | `http://localhost:8080/login/oauth2/code/google` |
| Staging | `https://staging-api.marketplace.com/login/oauth2/code/google` |
| Production | `https://api.marketplace.com/login/oauth2/code/google` |

> ⚠️ "It may take 5 minutes to a few hours for changes made to these settings to
> take effect."

### 5.5 Copy the Client ID and Client Secret

After creating the client:
1. Copy the **Client ID** (ends in `.apps.googleusercontent.com`).
2. Copy the **Client Secret**.
3. Store both as environment variables:

```bash
export GOOGLE_CLIENT_ID=123456789-abc123.apps.googleusercontent.com
export GOOGLE_CLIENT_SECRET=GOCSPX-abc123def456
```

> "Your application's client secret will only be shown after you create the
> client. Store this information in a secure place such as Google Cloud Secret
> Manager because it will not be visible or accessible again." — official doc.

### 5.6 Configure the application

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID:}
            client-secret: ${GOOGLE_CLIENT_SECRET:}
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: openid,profile,email
```

### 5.7 Why these scopes?

> **Official doc** — <https://developers.google.com/identity/openid-connect/openid-connect>:
>
> "The scope parameter must begin with the `openid` value and then include the
> `profile` value, the `email` value, or both."

- `openid` — required to receive an ID Token (OIDC)
- `profile` — user's name, profile picture URL
- `email` — user's email address and `email_verified` claim

No other Google APIs need to be enabled for plain OIDC login.

**Reference**: <https://support.google.com/cloud/answer/6158849>

---

## 6. JWT signing keystore

**Used for**: signing access tokens and ID tokens issued by the embedded
Spring Authorization Server.
**Algorithm**: RSA-2048 (asymmetric — private key signs, public key verifies).

### 6.1 Generate the keystore

```bash
keytool -genkeypair \
  -alias marketplace-auth \
  -keyalg RSA \
  -keysize 2048 \
  -keystore /opt/marketplace/keystore.jks \
  -validity 365 \
  -storepass change_me \
  -keypass change_me \
  -dname "CN=marketplace, OU=Engineering, O=Marketplace, L=Riyadh, ST=Riyadh, C=SA"
```

### 6.2 Configure the application

```bash
export JWT_KEYSTORE_PATH=/opt/marketplace/keystore.jks
export JWT_KEYSTORE_PASSWORD=change_me
export JWT_KEY_ALIAS=marketplace-auth
export JWT_KEY_PASSWORD=change_me
export AUTH_SERVER_ISSUER=https://api.marketplace.com
```

### 6.3 Key rotation (NIST SP 800-57)

The project uses `RotatingJWKSource` which maintains an **active** key and a
**previous** key with an overlap window. Rotation happens automatically every
90 days (`@Scheduled`). During the overlap, tokens signed by the previous key
remain valid until they expire.

> **Reference**: NIST SP 800-57 §8 recommends a cryptoperiod of 1–2 years for
> asymmetric signing keys. The 90-day rotation is intentionally more
> conservative.

**Spring reference**: <https://docs.spring.io/spring-security/reference/servlet/oauth2/server-authorization/jwk.html>

---

## 7. Environment variable reference

Create a `.env` file (or export these in your shell) before starting the app.
**Never commit `.env` to git** — it's in `.gitignore`.

```bash
# ─── PostgreSQL ───────────────────────────────────────────────
DB_URL=jdbc:postgresql://localhost:5432/marketplace
DB_USERNAME=marketplace
DB_PASSWORD=change_me

# ─── Redis ────────────────────────────────────────────────────
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# ─── SMTP (MailHog for dev, real provider for prod) ──────────
MAIL_HOST=localhost
MAIL_PORT=1025
MAIL_USERNAME=
MAIL_PASSWORD=

# ─── GitHub OAuth2 ───────────────────────────────────────────
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=

# ─── Google OAuth2 ───────────────────────────────────────────
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=

# ─── JWT signing ─────────────────────────────────────────────
JWT_KEYSTORE_PATH=/opt/marketplace/keystore.jks
JWT_KEYSTORE_PASSWORD=change_me
JWT_KEY_ALIAS=marketplace-auth
JWT_KEY_PASSWORD=change_me
AUTH_SERVER_ISSUER=http://localhost:8080

# ─── CORS ────────────────────────────────────────────────────
CORS_ALLOWED_ORIGINS=http://localhost:3000

# ─── Dev-only seed data (dev profile only) ───────────────────
DEV_ADMIN_PASSWORD=change_me
DEV_CLIENT_SECRET=change_me
OAUTH2_REDIRECT_URI=http://127.0.0.1:3000/login/oauth2/code/marketplace-web-client
```

A ready-to-use template is committed at `.env.example` — copy it and fill in
your values:

```bash
cp .env.example .env
# edit .env with your real values
```

---

## Official documentation index

| Service | Official doc URL |
|---------|------------------|
| Spring Boot deployment | <https://docs.spring.io/spring-boot/how-to/deployment/index.html> |
| Spring Boot AOT cache | <https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html> |
| Spring Boot Mail | <https://docs.spring.io/spring-boot/reference/io/email.html> |
| Spring Boot Redis | <https://docs.spring.io/spring-boot/reference/data/nosql.html#data.nosql.redis> |
| Spring Boot Flyway | <https://docs.spring.io/spring-boot/how-to/data-initialization.html> |
| Spring Security OAuth2 Client | <https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html> |
| Spring Authorization Server (JWK) | <https://docs.spring.io/spring-security/reference/servlet/oauth2/server-authorization/jwk.html> |
| Spring Security headers | <https://docs.spring.io/spring-security/reference/servlet/exploits/headers.html> |
| GitHub OAuth Apps | <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app> |
| GitHub OAuth scopes | <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps> |
| Google OAuth 2.0 setup | <https://support.google.com/cloud/answer/6158849> |
| Google OpenID Connect | <https://developers.google.com/identity/openid-connect/openid-connect> |
| OWASP Authentication Cheat Sheet | <https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html> |
| NIST SP 800-57 (Key Management) | <https://nvd.nist.gov/800-57> |
| Maven guides | <https://maven.apache.org/guides/index.html> |

---

## Change log

| Date | Change |
|------|--------|
| 2025-01-01 | Initial version — covers PostgreSQL, Redis, SMTP, GitHub, Google, JWT keystore. Verified against Spring Boot 4.1.0 and the official provider docs. |
