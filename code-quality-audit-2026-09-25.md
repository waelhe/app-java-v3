# Code quality audit — 2026-09-25

Baseline audit of the whole repository (not a subsystem), scored across five
dimensions. Every number below is either a count obtained by search or a
`file:line` reference. Anything that could not be verified is labelled
**unverified** rather than estimated.

**Method.** Three dimensions (maintainability, clean architecture, code
quality) were measured by delegated agents reading the tree, with the
high-value claims re-checked directly: module count 22, zero `printStackTrace`
in main sources, `git ls-files scripts` = 0, the five stale documentation
numbers in the table below, and the module/`pom.xml` boundaries. Security and
performance were measured directly. One security agent returned output
unrelated to this repository and was discarded; no claim here rests on it.

**Weighting.** security 25% · clean architecture 20% · maintainability 20% ·
performance 20% · code quality 15%. Security carries the most weight because
this is a production marketplace holding credentials, payments and PII.

| Dimension | Score | Source of the number | Confidence |
|---|---:|---|---|
| security | 8.2 | direct measurement | medium — per-endpoint authorization audit incomplete |
| performance | 8.3 | direct measurement | medium-high |
| clean architecture | 7.0 | agent + verified sample | high |
| code quality | 7.2 | agent + verified sample | high |
| maintainability | 6.4 | agent + six numbers verified here | high |

Weighted total: **7.47 / 10**.

---

## 1. security — 8.2/10

### Controls verified

| Control | Evidence | Official basis |
|---|---|---|
| Delegating password encoder (bcrypt with `{bcrypt}` prefix) | `marketplace-platform-infra/src/main/java/com/marketplace/shared/security/SecurityConfig.java:272-273` — `PasswordEncoderFactories.createDelegatingPasswordEncoder()` | Spring Security, password encoders |
| No hardcoded secret in main code | 456 main sources scanned for `sk_`/`pk_`/`whsec_`/`gho_`/`AKIA`/PEM headers: **0 hits**. The single match is a header-stripping string in a dev tool, `marketplace-app/src/test/java/com/marketplace/dev/DevJwtGenerator.java:74` | — |
| Parameterized native SQL | 14 `nativeQuery = true` sites, all with named parameters, `Pageable` and `countQuery`; 0 concatenations — e.g. `marketplace-catalog/src/main/java/com/marketplace/catalog/ProviderListingRepository.java:127-136` | Spring Data JPA, `@Query` |
| Bean validation present | 41 `@RequestBody` endpoints · 43 `@Valid` · 104 constraint annotations · 157 record DTOs | Jakarta Bean Validation |
| Public surface enumerated | 12 `permitAll` rules in `SecurityConfig.java:153-214` — public reads (listings/reviews/search/geo/sitemap), `actuator/health|info`, `/v3/api-docs`, `payments/webhooks/**`, `listings/*/leads` POST, `/assets/**`, `/login` | Spring Security filter chain |
| No client-facing exception leakage | `marketplace-shared/src/main/java/com/marketplace/shared/api/GlobalExceptionHandler.java:145,158` forward `IllegalArgumentException`/`IllegalStateException` messages — application-thrown text, not SQL or stack traces. The 23505 path extracts a message for a log line only (`:175`, `:210`). Taxonomy messages are externalized via `messageSource` (`:256`). 0 `printStackTrace` in main. | RFC 7807 |

### Gaps (verified)

- The two abuse-prone `permitAll` surfaces — `payments/webhooks/**` and the
  `listings/*/leads` POST — rely on signature verification and rate limiting
  respectively. The rate-limiter wiring was not re-verified in this pass.
- `getMessage()` appears 31 times in main sources; all 31 were not individually
  traced. Only the two handler sites reach a response.

### Unverified

- Per-endpoint IDOR review (ownership predicates on reads by id) — ownership
  gates exist for reviews (`ReviewsOwnershipGateIntegrationTest`,
  `ReviewsTwoWayIntegrationTest` in `marketplace-app/src/test/java/com/marketplace/reviews/`) but the sweep was not completed.
- MFA: absent. Known and previously recorded, not re-measured here.
- GraphQL depth/complexity limits.
- Contents of the deployed production image.

---

## 2. performance — 8.3/10

| Measurement | Value | Evidence |
|---|---:|---|
| Unbounded `findAll()` in main code | 3 | `marketplace-geo/src/main/java/com/marketplace/geo/GeoService.java:71` (reference data), `marketplace-pricing/src/main/java/com/marketplace/pricing/PricingService.java:281` (rules) |
| Batch rehydration instead of per-row loads | present | `marketplace-catalog/src/main/java/com/marketplace/catalog/CatalogService.java:387` `findAllById(idsInOrder)`, `marketplace-identity/src/main/java/com/marketplace/identity/IdentityProviderNameResolver.java:31` |
| Caches | 15 names, `-v2` versioned, event-driven invalidation (0 `@CacheEvict`) | `marketplace-app/src/main/resources/application.yml:188-212` |
| Cache key discrimination | page, size, sort, plus query/category | `CatalogService.java:80,94,149` |
| Entry TTL | 1h, fixed expiry, documented as bounded by the AFTER_COMMIT relay | `application.yml:203-212` |
| Connection pool | max 20, min-idle 5, max-lifetime 1800s, timeout 30s; prod overridable via `DB_POOL_SIZE`/`DB_POOL_MIN` | `application.yml:51-55`, `application-prod.yml:7-8` |

**Gaps.** Six production classes exceed 400 lines, which is where query and
mapping logic concentrates (overlaps maintainability). Pool 20 against a 1 GB /
2 vCPU container and a previously recorded "too many clients" incident in the
shared test database. 20 `Thread.sleep` sites in tests (flakiness, not
production throughput).

**Unverified.** No live slow-query baseline; no EXPLAIN sampling of the 14
native queries; not measured whether the search fallback query's `word_similarity`
trigram path holds at current row counts.

---

## 3. clean architecture — 7.0/10

### Enforcement map

| Rule | Enforced by |
|---|---|
| 22-module reactor, 58 internal edges | `pom.xml:21-44`; Maven topology only — a semantic allow-list is **not** enforced |
| Spring Modulith module verification | `marketplace-app/src/test/java/com/marketplace/ModulithVerificationTest.java:18-20` |
| Per-module `allowedDependencies` (20/20 declared) | e.g. `marketplace-messaging/src/main/java/com/marketplace/messaging/package-info.java:2-10` |
| No controller→repository, no service→controller, acyclic slices | `marketplace-app/src/test/java/com/marketplace/ArchitectureRulesTest.java:28-47` — 11 rules |
| Transaction placement | `docs/CODING_STANDARDS.md:115-163` — **documented only, unenforced** |
| Package layering, entity-free controller signatures, framework-neutral ports | **unenforced** |

### Findings

1. **high** — `marketplace-app/src/main/java/com/marketplace/app/websocket/MessagingWebSocketController.java:3-6` imports the messaging JPA entity, and `MessagingService.sendMessage` returns it (`marketplace-messaging/src/main/java/com/marketplace/messaging/MessagingService.java:177-182`). Return a DTO; keep `Message` internal.
2. **medium** — four controllers expose JPA entities in 9 endpoint signatures: `AvailabilityController.java:30-76`, `NotificationController.java:29-37`, `LedgerController.java:22-31`, `ProviderLedgerController.java:55`.
3. **medium** — packages are flat in all 18 domains (0 `api`/`internal`/`persistence` directories), e.g. `marketplace-catalog/.../catalog/package-info.java:1-5`.
4. **medium** — 17/18 domains depend directly on `marketplace-platform-infra` while owning concrete adapters: `marketplace-media/src/main/java/com/marketplace/media/S3MediaStorage.java:3-21`, `marketplace-payments/src/main/java/com/marketplace/payments/StripePspChannel.java:3-11`.
5. **medium** — six classes exceed 400 lines (`CatalogService.java:52` 742, `SecurityConfig.java:89`, `PaymentsService.java:42`, …) and three beans exceed seven constructor collaborators (`MediaService` 8, `PaymentsService` 9, `SavedSearchService` 9). 0 production methods exceed 80 lines.
6. **medium** — `marketplace-shared` places 87 main files in one package and couples 24 ports to Spring `Page`/`Pageable`/`Authentication` (e.g. `CatalogSearchPort.java:3-4`).
7. **low** — 7 switch-on-type sites; `ContentReportService.java:130,163,191` and `PaymentsService.java:198` centralize dispatch that strategy objects would carry.

### Strengths

Zero domain→app dependencies. Exactly two cross-domain imports, both the
intentional `CatalogSpi` (`LeadsService.java:3`, `RealestateService.java:3`).
31/31 entities audited. 151 production `@Transactional`, of which 121 sit in
`*Service` and 0 on controllers or private methods.

---

## 4. code quality — 7.2/10

Scope: 771 first-party Java files (456 main, 315 test).

### Measured

| Metric | Value |
|---|---:|
| Repeated Testcontainers pair in integration tests | 35 of 74 classes |
| Duplicated OAuth test constants (client id, secret, callback, paths) | 5 files (authorize path 7, token path 8) |
| Unused imports | 44 across 37 files (15 in main) |
| Files without a final newline | 39 Java, 10 SQL |
| Public methods with no repo-wide reference | 2 (`CatalogService.java:226`, `PropertyCriteria.java:40`) |
| Unused logger fields | 3 (`BookingExpirationService.java:16`, `NotificationService.java:26`, `EventPublicationResubmission.java:70`) |
| Methods over 80 lines | 8, all in tests (longest 264) |
| Classes over 400 lines | 12 (6 production) |
| Swallowed exceptions in main | 1 (`CacheInvalidationRelay.java:79`) |
| `Thread.sleep` in tests | 20 sites, 3 directly inside `@Test` |
| Public classes with class-level Javadoc | 124 / 215 |
| String literals occurring in ≥3 files | 86 distinct, 575 occurrences |
| Commented-out code blocks | 0 |
| Disabled tests | 0 |
| Ad-hoc error responses bypassing RFC 7807 | 0 |
| Dead migrations / constant-false flags | 0 (70 migrations: 68 versioned + 2 repeatable, V35 retired) |

### Findings

- **medium** — the same 10-line PostgreSQL/Redis setup in 35 classes, e.g.
  `AdminModuleIntegrationTest.java:35`, `IdentityModuleIntegrationTest.java:33`,
  `SearchModuleIntegrationTest.java:38`. Extract a shared factory while keeping
  the per-class `@Container` fields that the isolation guard requires.
- **medium** — five classes redeclare the same authorization-server fixture,
  e.g. `AccountPseudonymizationIntegrationTest.java:188` and
  `AuthorizationServerLoginGateIntegrationTest.java:114`.
- **low** — cross-layer literals (`"status"`, `"listing_id"`, `"COMPLETED"`,
  `"created_at"`) span Java and SQL; centralize where the coupling is intended.
- **low** — a domain method takes an `Optional` parameter
  (`RealEstateListingJsonLd.java:99`) and five `!= null` chains exceed two
  comparisons (`SearchCriteria.java:201`).
- **low** — 44 unused imports and 39 files without a trailing newline.

### Strengths

Central taxonomy consistently used (`ApiErrorTaxonomy.java:10`,
`GlobalExceptionHandler.java:46`, and security responses via
`SecurityConfig.java:363`). Production method size is disciplined: 0 over 80
lines, longest 66 (`OAuth2ClientSecretInitializer.java:110`).

---

## 5. maintainability — 6.4/10

### Stale numeric claims (all six verified during this audit)

| Documented | Location | Counted |
|---|---|---|
| 21 Maven modules | `SYSTEM.md:24` | 22 |
| 34 `*IT` integration tests | `SYSTEM.md:55` | 2 `*IT`; 74 files selected by Failsafe |
| 14 `ModuleIntegrationTest` classes | `SYSTEM.md:166` | 22 |
| 13 named caches | `SYSTEM.md:145` | 15 |
| architecture comprises 16 modules | `docs/architecture/ARCHITECTURE.md:25` | 22 |
| migration history: 70 files, V35 absent | `SYSTEM.md:142` | match (70 = 68 V + 2 R) |

Additionally, `SYSTEM.md §10` cites saved official-document copies under
`scripts/**`; that tree is not in the repository — `git ls-files scripts`
returns 0 — so those citations cannot be verified from the project.

### Findings

- **high** — six stale/unauditable claims, above. The project's own map
  misinforms every reader and every tooling session.
- **medium** — `PROJECT_MAP.md:3` opens with a PR journal rather than a
  current-state index; `SYSTEM.md:3` is a mechanism map carrying extensive
  chronological history. `docs/architecture/ARCHITECTURE.md` is the dedicated
  map and is the stale one.
- **medium** — the coverage gate is module-local
  (`pom.xml:471-548`, JaCoCo 0.8.15, `COVEREDRATIO ≥ 0.70`, 26 exclusions)
  while `marketplace-catalog` has 27 main and 0 module-local test files, so the
  largest domain module's gate is effectively absent.
- **medium** — comments are 37.08% of non-blank main lines (10,334 of 17,534).
  Many carry measured WHY with citations (e.g.
  `ProviderListingSpecifications.java:122-151`, `AiChatGateway.java:24-33`),
  but some restate the code (`ContentReportService.java:197`,
  `LedgerService.java:112-117`) and some narrate past changes.
- **medium** — 601 YAML mapping keys across 6 `application*.yml` files, with 31
  duplicated path/value pairs; a comment at `application.yml:186` calls
  `geo-tree` the 14th cache while 15 are declared.
- **medium** — no root README; the nearest entry point is
  `docs/phase-0-baseline-checklist.md:10-19`, and `docs/clean-development-plan.md:251`
  shows bare `mvnw` forms that do not resolve in PowerShell or Bash.
- **low** — 13 guard classes / 71 gate units are strong, but 5 of the 6 process
  rules in `SYSTEM.md §14.1` have no executable mapping.

### Strengths

11 ArchUnit rules + module verification, with test naming aligned exactly to the
Surefire/Failsafe includes (`pom.xml:564-584`: 238 unit / 74 integration files).
A deterministic migration-checksum guard exists
(`marketplace-app/src/test/java/com/marketplace/app/MigrationChecksumGuardTest.java:71-121`).
Domain vocabulary is consistent — no duplicate aggregate noun among
Listing/Property/Ad/Announcement, User/Account/Principal,
Payment/Transaction/Charge, Message/Conversation/Thread.

---

## Top five, by priority

1. **Documentation numbers lie** (the table above). Fix with a guard in
   `PlatformGovernanceFilesTest` that derives each figure from the reactor,
   the file tree, `application.yml` and the migration folder, and fails on
   drift — the pattern already used by `containerScanIgnoresAreBoundedEntries`.
   Each documented figure also needs one canonical phrasing, since a wrong
   interpretation would merely install a new wrong number.
2. **The coverage gate does not cover `marketplace-catalog`**
   (`pom.xml:471-548`). Add catalog tests or an aggregate report/check, then
   verify the generated gate.
3. **Entities cross the HTTP boundary**
   (`MessagingWebSocketController.java:3`, four controllers / nine signatures).
   Map to DTOs and add an ArchUnit rule for entity-free controller signatures.
4. **Duplication that compounds** (35/74 container blocks, 5 OAuth fixtures, 44
   unused imports). Extract a shared test fixture; keep the per-class
   `@Container` fields the isolation guard depends on.
5. **Architecture rules without gates** — transaction placement, package
   layering, framework-neutral ports. Add the missing ArchUnit rules so drift
   fails the build instead of waiting for review.

## Not verified in this pass

Per-endpoint IDOR review · MFA (absent, previously recorded) · GraphQL
depth/complexity limits · rate-limiter wiring on the permitAll write surfaces ·
live slow queries · production image contents · the two controller-signature
and architecture claims that came from delegated agents and were not re-read
line by line.
