# Codex: Framework — Official Corpus (live-verified)

> The rule of rules: **every framework claim answers to the official docs.** This file is the verified
> corpus the system is built on. Verify date: **2026-09-11** — all URLs below are live and current for
> Boot 4.1.1 / Spring Framework 7.0.9 / Security 7.1.1 / Modulith 2.1.1 / Maven 3.9.x / Java 25.
> If a clash with newer official text appears, the newer official text wins; record a decision.

---

## 1. Spring Boot 4.1.1

### 1.1 Application structure & configuration
- `@SpringBootApplication` = `@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@ComponentScan`
  (docs: `reference/using/using-the-springbootapplication-annotation.html`).
- Place it in the **root package** so scan + auto-config cover the whole app; only **one** such annotation.
- If `@ComponentScan` is replaced by `@Import`, `@Component`/`@ConfigurationProperties` classes are no longer picked up.
- Auto-configuration **backs off** when you define your own bean (e.g. your own `DataSource` disables the embedded fallback);
  never replace auto-configuration wholesale — replace the specific bean (docs: `reference/using/auto-configuration.html`).
- Auto-config class names are public only for `exclude`/`spring.autoconfigure.exclude`; their contents are internal API — do not use directly.
- `--debug` prints the conditions report (what configured & why).

### 1.2 Dependency injection
- **Constructor injection is recommended**; favor it over field injection (docs: `reference/using/spring-beans-and-dependency-injection.html`).
- Final fields + constructor → immutable, testable, explicable; a bean with 3+ collaborators is a smell → split.
- This is a Modulith: cross-module collaboration happens via **application events**, not bean injection into other modules.

### 1.3 Externalized configuration
- Property sources are ordered; later sources override earlier ones; profile-specific files override the default file
  (docs: `reference/features/external-config.html`).
- Environment variables use **relaxed binding** (`MY_PROP` ≡ `my.prop`); are the prod entry point for secrets.
- `@ConfigurationProperties` (constructors/records) for structured binding; `@Value` only for one-off reads.
- `@DefaultValue` for absent nested sections; `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase` for graceful shutdown
  (docs: `reference/web/graceful-shutdown.html` — graceful is default on all three embedded servers).

---

## 2. Spring Framework 7.0.9

### 2.1 Declarative transactions — the proxy model
(docs: `reference/data-access/transaction/declarative/*` + `.../tx-decl-explained.html`)
- `@Transactional` works **through the AOP proxy**: only *external* calls are intercepted; **self-invocation is not transactional**.
- Method-level settings override class-level (most derived location wins). Default: `public` methods; interface proxies require `public`.
- Default rollback: any `RuntimeException`/`Error`; checked exceptions do **not** roll back.
- `readOnly` only meaningful for `REQUIRED`/`REQUIRES_NEW`; set `readOnly=true` on read paths; `REQUIRES_NEW` for isolated units (e.g. audit).
- Apply on **Service** layer, never on the Controller.

### 2.2 Method security (docs: Security 7.1.1 `reference/servlet/authorization/method-security.html`)
- `@EnableMethodSecurity` is **not** enabled by default — unannotated methods are NOT secured; a catch-all
  `anyRequest().authenticated()` in `HttpSecurity` is the net below them.
- Use **both**: request-level coarse + method-level fine = defense in depth.
- Prefer authorities over complex SpEL; `@PostAuthorize` guards IDOR (read) but is **not recommended** on DB-write
  methods/transactions (ordering of transaction vs authorization) — authorize writes at entry.

---

## 3. Spring Modulith 2.1.1 (docs: `reference/index.html`, `reference/events.html`)

- Modules interact through **events + public APIs**, never internal beans; publication is **synchronous by default**
  — transactional semantics stay consistent (composer + listener commit/rollback together).
- `@ApplicationModuleListener` = `@Async` + `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener` —
  listener runs in its own transaction, asynchronously.
- **Event Publication Registry** writes each publication into a log within the original transaction; on listener success it is marked complete;
  a staleness monitor auto-fails stuck publications. Completion modes: `UPDATE` (default) / `DELETE` / `ARCHIVE`.
- Events are named in the **past tense** (`OrderPlaced`); the emitter must not know the consumers.
- 17 application modules are the current map (see `SYSTEM.md` §10 deep-dive tree). Adding a module = level-4 gate.

---

## 4. Maven

### 4.1 Lifecycle (docs: maven.apache.org — introduction to the lifecycle)
- Built-in lifecycles: **default, clean, site**; default phases run **sequentially** — `validate → compile → test → package → verify → install → deploy`.
- `mvn verify` runs everything up to and including `verify`; only ever call the **last** phase you need.
- Goals bind to phases (order = declaration order in the POM); hyphenated phases are not invoked directly.
- Reactor: `mvn -pl <module> -am test` builds only the module + its dependencies — the per-module verification command.

### 4.2 Dependency law
- Only versions compatible with Boot 4.1.1, documented officially; **never pin versions the BOM manages**.
- Community exceptions registry (root `pom.xml`): springdoc 3.0.3, MapStruct 1.6.3, Instancio 6.0.0-RC3. Anything else = documented exception.

---

## 5. Flyway

- Migrations are **immutable**: once applied (`V{n}__*.sql` on the DB) they are never edited — a change is a new `V{n+1}`.
- History is the source of truth for schema; `baseline` only when adopting an existing DB.
- `R{n}__` for repeatable; checksums in `flyway_schema_history` guard against tampering.

---

## 6. Java 25

- LTS, current platform (2026). Records/sealed types/pattern matching are idiomatic for DTOs + dispatch.
- Prefer records for immutable carriers (`@ConfigurationProperties`, events, responses); `Optional` for presence, not for control flow.
- Project toolchain is fixed by the root POM; do not raise/lower the source/target level without a decision.

---

## 7. REST errors — RFC 9457 (Problem Details)

- Errors are returned as `application/problem+json` (RFC 9457, the successor of RFC 7807).
- Standard fields: `type`, `title`, `status`, `detail`, `instance`; extensions allowed for machine-readable context.
- Never leak internals (stack traces, SQL, secrets) in `detail`/extensions; see `docs/api/error-contract.md` + `docs/api/error-codes.md`.

---

## 8. Citation duty

- Any claim attributed to the framework carries: doc URL + section, or `file:line` for repo facts.
- Unverifiable memory claims are labeled as such and replaced by a search before being used as a basis for a decision (level ≥ 2).
- Two official sources collide → the **user decides**, with both citations in front of them.

---

## 9. Source routing — consult FIRST per task

This corpus is consulted through a routing table: each task class designates **one governing source and its entry
section**, read at the latest stable version for this stack (Boot 4.x, Spring 7, Modulith 2.x, SAS 7.x, Java 25).

| Task type | Source to consult first | Entry point section |
|---|---|---|
| Boot features, properties, auto-config | `https://docs.spring.io/spring-boot/reference/` | Feature area (Externalized Configuration, Auto-Configuration) |
| Spring Boot how-tos (DB/Redis/Mail…) | `https://docs.spring.io/spring-boot/reference/` → "How-to guides" | relevant how-to |
| Core/IoC/AOP/transactions | `https://docs.spring.io/spring-framework/reference/` | Core → Bean Overview / Transactions |
| Modulith events, modules, moments | `https://docs.spring.io/spring-modulith/reference/` | Events / Application Modules / Moments |
| Security (method SpEL, OAuth2/JWT) | `https://docs.spring.io/spring-security/reference/` | Authorization → Method Security / Resource Server |
| Authorization Server | `https://docs.spring.io/spring-authorization-server/reference/` | Protocol endpoints / Clients |
| JPA/Repositories | `https://docs.spring.io/spring-data/jpa/reference/` | Repositories / Auditing / Query Methods |
| Maven build, lifecycle, plugins | `https://maven.apache.org/guides/` + `https://maven.apache.org/pom.html` | Lifecycle / POM Reference |
| Official end-to-end guides | `https://spring.io/guides` | category per technology |
| Flyway migrations | `https://documentation.red-gate.com/flyway/` | migration naming/order |
| Community exceptions (MapStruct/springdoc/Instancio) | their official reference docs | mapping / annotations / generation |

Binding rules (consult-before-execute):
1. Any step touching a framework/dependency feature **reads the governing section first, then applies** — never "I remember this works".
2. **Cite:** the document + section is named in the task brief (`Governance Codex §4.2`), matching rule D (Governance Codex §3).
3. **Version fit:** the **current stable** docs are read; old blogs/StackOverflow memory is only a *hypothesis*, confirmed against the official source.
4. **Conflict:** source vs memory → source wins (§8). Source ambiguous → the question goes to the user.
5. **Two official sources disagree** → both are offered to the user with citations; never settled by opinion.

---

*This is the official corpus. It is consulted before any framework/dependency touch, and updated only when the
referenced official docs change (with a verified date line).*