# labs/reference — Isolated Official Reference

> **Zero-scope build.** A clean, isolated reference derived exclusively from OFFICIAL
> documentation. No internal repo data, no current governance system, no Phase-1 carryover.
> Foundation: `official-tree.md` — the verified inventory of every family/sub-family for our stack.
>
> Rules: this folder does NOT depend on anything under `docs/governance/`; it is self-contained.

## Layout

| File | Content |
|---|---|
| `official-tree.md` | **Foundation.** Full family tree (Boot/Framework/Security/JPA/Modulith/Maven/Flyway/Java/RFC/OWASP) with stack ⭐/⭕ and scan flags. Nothing excluded. |
| `01-core.md` | IoC / DI / Beans / Validation / AOP / SpEL |
| `02-web.md` | Spring MVC + Servlet + Error/ProblemDetails + CORS + API Versioning |
| `03-security.md` | Spring Security 7.1.1 + SAS: authn / authz / CSRF / JWT |
| `04-data.md` | JPA / JDBC / Transactions / Locking / Auditing / Envers |
| `05-modulith.md` | Modules / Events / Verification / Testing |
| `06-build.md` | Maven / Flyway / Java 25 |
| `07-testing.md` | TestContext / Slices / Parallel |
| `08-ops.md` | Actuator / Observability / AOT / Packaging |
| `09-api-web.md` | RFCs + OWASP/NIST |

## Usage rules

1. Each family file is loaded **on demand** (Modulith-style) — not all at once.
2. Every item carries its official source URL + tree ref (e.g. `2.8`).
3. Distinguish clearly: **framework rule** (from official docs) vs **project convention** (ours, marked explicitly).
4. Nothing here is sourced from memory or from the current system — only from official docs + the tree.
5. Gaps found during verification (see tree "Cross-cutting lessons") are inserted at their tree home.

## Generate order

1. `official-tree.md` (foundation — done)
2. `01-core.md` … `09-api-web.md` (systemic deep-scan, one family file per pass, each verified)
3. `README.md` updated after each pass.

## Sources (verified live 2026-09-12)

- Spring Boot 4.1.1 / Framework 7.0.9 / Security 7.1.1 / JPA 4.1.1 / Modulith 2.1.1 — docs.spring.io
- Maven 3.9.x — maven.apache.org
- Flyway 12.x — documentation.red-gate.com
- Java 25 — dev.java (docs.oracle.com 403)
- RFCs 9457/7519/7009/7662/6749 — rfc-editor.org
- OWASP Top 10 + cheat sheets + NIST — owasp.org / NIST