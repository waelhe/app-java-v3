# 09 — API Contracts & Security: RFCs + OWASP/NIST (isolated official reference)

> **Tree refs:** 9.1 (RFC 9457), 9.2 (RFC 7519 JWT), 9.3 (RFC 9470), 9.4 (OAuth2), 10.1-10.5 (OWASP Top 10 + cheat sheets + NIST).
> **Sources:** `https://www.rfc-editor.org/rfc/` + `https://owasp.org/` (cheat sheets) + NIST guidance — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. **Isolation:** no internal repo data. **Encoding:** UTF-8.

---

## 1. RFC 9457 — Problem Details (9.1)

- MUST | Return errors as `application/problem+json` (RFC 9457, the successor of RFC 7807). | [paraphrase] | rfc-editor.org/rfc/rfc9457
- MUST | Standard fields: `type` (URI), `title`, `status`, `detail`, `instance`; extensions allowed for machine-readable context. | [paraphrase] | rfc-editor.org/rfc/rfc9457
- MUST | `type` should be a URI that a client can dereference for the problem's semantics. | [paraphrase] | rfc-editor.org/rfc/rfc9457
- AVOID | Leaking internals (stack traces, SQL, secrets) in `detail`/extensions. | [paraphrase] | rfc-editor.org/rfc/rfc9457 · 02-web.md §6

## 2. RFC 7519 — JWT (9.2)

- MUST | JWT claims must have unambiguous, unique names; use `jti` for replay/tracking; set `cty` when nesting a signed token inside an encrypted one. | [paraphrase] | rfc-editor.org/rfc/rfc7519
- MUST | Choose the `alg` deliberately; avoid `alg: none` except with a documented justification — an unsecured JWT must be clearly marked (`alg: none` + `typ: JWT`). | [paraphrase] | rfc-editor.org/rfc/rfc7519
- ATTEND | Validate expiry/`iat` with a bounded clock skew; never trust a token whose claims are stale or unsigned. | [paraphrase] | rfc-editor.org/rfc/rfc7519
- AVOID | Putting PII in a JWT without an explicit need (RFC 7519 §10 privacy); prefer references/correlation ids over embedded personal data. | [paraphrase] | rfc-editor.org/rfc/rfc7519

## 3. OAuth2 / Token Lifecycle (9.4, 9.3)

- MUST | Use standard grant flows (authorization code + PKCE for clients, client credentials for server-to-server); validate redirect URIs exactly. | [paraphrase] | rfc-editor.org/rfc/rfc6749 · 03-security.md §5
- MUST | Support token introspection/revocation (RFC 9470/7662) where scope requires; revoke tokens at logout/compromise. | [paraphrase] | rfc-editor.org/rfc/rfc9470
- ATTEND | Prefer sign-then-encrypt (JWS then JWE) when both integrity and confidentiality of a token are required. | [paraphrase] | rfc-editor.org/rfc/rfc7516 · 03-security.md §5

---

## 4. OWASP Top 10 (10.x)

- MUST | A01 Broken Access Control: enforce object-level authorization — actor A must not read/write actor B's rows; test both paths. | [paraphrase] | owasp.org — A01 (also 03-security.md §3)
- MUST | A03 Injection: validate at the boundary; never build SQL/commands/expressions by string concatenation — use repository params, JdbcTemplate params, SpEL safely. | [paraphrase] | owasp.org — A03
- MUST | A07 Authentication failures: strong password storage (adaptive one-way), session management, MFA where appropriate. | [paraphrase] | owasp.org — A07
- MUST | Encrypt data in transit always (TLS); secrets via env/secret-manager, never committed to git. | [paraphrase] | OWASP secrets management cheat sheet

## 5. OWASP Cheat Sheets (10.4)

- MUST | Logging: never log passwords, tokens, JWTs, or card data; implement redaction. | [paraphrase] | OWASP logging cheat sheet
- MUST | CORS: do not use `*` with credentials; use `allowOriginPatterns` or a finite origin set (see 02-web.md §4). | [paraphrase] | OWASP CORS cheat sheet
- MUST | JWT: validate signature, issuer, audience, expiry; protect the signing key; use a strong `alg`. | [paraphrase] | OWASP JWT cheat sheet
- MUST | Input validation: validate and normalize all input at the boundary; use allow-lists. | [paraphrase] | OWASP input validation cheat sheet
- MUST | Secrets management: store secrets in env/secret-manager; rotate; never hard-code. | [paraphrase] | OWASP secrets management cheat sheet

## 6. NIST & Money (10.5)

- MUST | Money/currency MUST use an exact decimal representation (`BigDecimal` in Java) — never binary floating point (`double`/`float`). | [paraphrase] | NIST guidance (exact decimal for currency)
- MUST | Rounding and precision decisions must be explicit (e.g. monetary scale + rounding mode), not default. | [paraphrase] | NIST guidance
- AVOID | Storing/deriving monetary values with `double`/`float` — precision loss on accumulation; use `BigDecimal` + a fixed scale in persistence and transport. | [paraphrase] | NIST guidance
- ATTEND | Cryptographic strength: use current recommended algorithms (e.g. SHA-256+, 2048+ RSA / ECDSA for JWK); avoid deprecated/weak algorithms. | [paraphrase] | NIST SP 800-57 (crypto guidance)

---

## Cross-cutting gap insertions (from the standards-miner)

| Gap | Home | Rule added |
|---|---|---|
| Money via `BigDecimal` (precision) | §6 | Exact decimal for money; never `double`/`float`; explicit scale + rounding. |
| RFC 9457 (7807 replaced) | §1 | Problem Details via `application/problem+json`; never leak internals. |
| JWT: unique claims, `jti`, `cty`, `alg` decision | §2 | Unique claim names, `jti`, `cty` for nesting, deliberate `alg`. |
| Defense in depth | → 03-security.md §3 | (owned by security family) |

## Verification note
- RFC pages verified live 2026-09-12 at rfc-editor.org; OWASP/NIST pages at owasp.org + NIST. Tree coverage: 9.1 ✅ · 9.2 ✅ · 9.3 ◐ · 9.4 ✅ · 10.1 ✅ · 10.2 ✅ · 10.3 ✅ · 10.4 ✅ · 10.5 ✅.