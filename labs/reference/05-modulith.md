# 05 — Spring Modulith 2.1.1 (isolated official reference)

> **Tree refs:** 5.1 (Fundamentals), 5.2 (Verification), 5.3 (Application Events), 5.4 (Integration Testing), 5.5 (Moments), 5.7 (Runtime), 5.8 (Production-ready).
> **Source:** `https://docs.spring.io/spring-modulith/reference/` @ 2.1.1 — verified live 2026-09-12.
> **Note:** in 2.1.1 `application-modules.html`/`actuator.html` return 404 → used `fundamentals.html`/`production-ready.html`.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. **Isolation:** no internal repo data. **Encoding:** UTF-8.

---

## 1. Fundamentals (5.1)

- MUST | Build domain-driven, modular applications: each logical part is an application module with a clear public API; modules interact via events + documented APIs, not internal bean injection. | [quote] | fundamentals.html
- MUST | Enforce module boundaries: a module's internal packages (`internal/`/`spi/`) must not be imported by other modules; the public API is the only allowed surface. | [paraphrase] | fundamentals.html
- ATTEND | Spring Modulith applies an opinion on functional structure, analogous to Boot's opinion on technical arrangement. | [quote] | index.html

## 2. Verifying Module Structure (5.2)

- MUST | Verify module structure in CI with `ApplicationModules.of(Application.class).verify()` (spring-modulith-test) — it fails the build on illegal cross-module dependencies. | [paraphrase] | testing.html
- ATTEND | The verification also detects circular module dependencies and unnameable public API (allows warning-level instead of error where configured). | [paraphrase] | testing.html

## 3. Application Events (5.3) — the core rule

- MUST | Publish domain events via `@ApplicationModuleListener` — the official shortcut that enables the triple `@Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener`; the listener runs in its own transaction, asynchronously. | [quote] | fundamentals.html
- ATTEND | Events are published synchronously by default in the composer's transaction (composer + listener commit/rollback together) unless using the async annotation. | [paraphrase] | fundamentals.html
- MUST | Use the **Event Publication Registry**: each publication is written to a log within the original transaction; a staleness monitor (activating only when a non-zero staleness interval is configured) marks stuck publications as **failed** — a failed publication is not resubmitted by the current mechanism. | [paraphrase] | fundamentals.html
- AVOID | Catching `Exception` broadly in an event listener — it prevents the registry from retrying a failed publication; let the failure surface so the retry mechanism can act. | [paraphrase] | fundamentals.html
- ATTEND | Quote: "To run a transactional event listener in a transaction itself, it would need to be annotated with @Transactional in turn." | [quote] | fundamentals.html
- MUST | Name events in the past tense (`OrderPlaced`); the emitter must not know the consumers (decoupling). | [paraphrase] | fundamentals.html
- ATTEND | Inside a listener, `@Transactional(propagation=NOT_SUPPORTED)` suspends the publisher's transaction and runs non-transactionally — distinct from `REQUIRES_NEW`, which starts a NEW independent transaction (see 04-data.md §6). | [paraphrase] | spring-framework (cross-ref, see 04-data.md §6)

## 4. Integration Testing Modules (5.4)

- MUST | Integration-test each application module in isolation (`@SpringBootTest` scoped to the module + its dependencies) rather than loading the whole application every time. | [paraphrase] | testing.html
- ATTEND | Modulith offers `@ApplicationModuleTest` (or `@ApplicationModuleIntegrationTest`) to bootstrap only a module's slice with its public API; combine with Testcontainers where a store is needed. | [paraphrase] | testing.html

## 5. Moments — Passage-of-Time Events (5.5)

- ATTEND | The **Moments** API emits time-based application events (e.g. `OrderCompletionDateReached`) from an `ApplicationModuleListener` to model business time; a `@Scheduled`/external trigger drives it. | [paraphrase] | moments.html
- ATTEND | Enables the "cron as a domain event" pattern so time-based logic lives in the domain, not in arbitrary schedulers. | [paraphrase] | moments.html

## 6. Runtime Support (5.7)

- ATTEND | Spring Modulith Runtime registers an `ApplicationEventPublication` (or per-module) repository bean and can be wired to the persistence context (JDBC/JPA) so publications survive restarts. | [paraphrase] | runtime.html
- ATTEND | Completion modes UPDATE (mark complete)/DELETE/ARCHIVE control registry growth; monitor stuck publications with the staleness mechanism. | [paraphrase] | fundamentals.html · runtime.html

## 7. Production-ready (5.8)

- ATTEND | Expose module structure via actuator: `/actuator/modulith` (module graph with dependencies). | [paraphrase] | production-ready.html
- ATTEND | The endpoint lists each module's outgoing dependencies including event listeners — a cross-reference for event consumers and for the naming/past-tense rule. | [paraphrase] | production-ready.html

---

## Cross-cutting gap insertions

| Gap | Home | Rule added |
|---|---|---|
| No `catch(Exception)` in event listeners (EPR retry) | §3 | Broad catch prevents registry retry; surface the failure. |
| Event naming past-tense (`BookingCreated`) | §3 | Name events in the past tense; emitter unaware of consumers. |
| Cross-module dependency avoidance | §1/§2 | Modules interact via events + public APIs; verified in CI. |

## Verification note
- Pages fetched live 2026-09-12 at Modulith 2.1.1; `application-modules.html`/`actuator.html` 404 → `fundamentals.html`/`production-ready.html`.
- Tree coverage: 5.1 ✅ · 5.2 ✅ · 5.3 ✅ · 5.4 ✅ · 5.5 ◐ · 5.6 ⭕ docs · 5.7 ◐ · 5.8 ✅ · 5.9 ⭕ artifact list.