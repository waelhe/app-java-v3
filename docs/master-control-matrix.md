# Master Control Matrix — Unified End-to-End User Journey Control Record

> **Purpose.** This document is the **single control record for platform user journeys (J-001..J-032)**. It exists to end the drift between plans and reality: a journey is never called "complete" — it carries a **measured status code** backed by **code evidence on a pinned commit**. It was commissioned by the owner's external study «تصميم رحلة المستخدم الموحدة» and is established by this wave (M0).
>
> **Governance position.** Official framework documentation (spring.io / docs.spring.io / github.com/spring-projects) remains the **absolute governor** for every technical decision. Inside the repository this matrix works **within** the existing governance chain — `AGENTS.md` (rules) → `SYSTEM.md` (mechanism) → `PROJECT_MAP.md` (truth log) → this matrix (journey state) → `docs/unified-platform-plan.md` (execution order). It creates **no competing specification**; it adds the journey dimension the chain lacked.
>
> **Measurement base.** Every status below was measured on main at **`38546731`** (squash of #543, 2026-10-13) unless a later commit is cited. Re-measure before flipping any status; a status without a commit citation is void.

---

## 1. Pentagonal status law

A journey has five independent sides. It may be released to production users only when **all five** prove out:

| Side | Meaning |
|---|---|
| **B** — Backend | Domain entity, endpoints, migrations, authorization rules exist and behave server-side |
| **A** — Android | Compose screens + Retrofit contracts wired to the real server contracts, real state flows |
| **T** — Test evidence | Integration/UI tests exist in the tree and pass on the pinned commit |
| **O** — Operations | Environment variables, provider keys, certificates, deploy path ready |
| **E2E** | Full journey proven from a real device through the server, DB, external provider and back |

### 1.1 Status codes (normative)

| Code | Meaning | Operational rule |
|---|---|---|
| `E2E-PASS` | Full journey passed all acceptance criteria, happy + negative, on a recorded run | **The only code that allows a user-facing release** |
| `BE-PROVEN` | Backend complete with passing integration tests on the pinned commit | Does **not** prove Android can run the journey |
| `BE-PRESENT` | Server paths/services exist but test coverage not proven | Forbidden to describe the feature as complete |
| `ANDROID-PARTIAL` | Client connects to some APIs; later steps or error paths missing | Forbidden to market the screen as a full experience |
| `CLIENT-GAP` | Server ready; client has no screens/contracts for this journey | Development priority goes to wiring the client, not rewriting the server |
| `DOMAIN-GAP` | Business capability not yet represented in the domain model/API | Requires domain analysis before UI work |
| `OPS-GATE` | Code ready; blocked on secrets/provider config/certificates | Blocks release until operational requirements are met |
| `DECISION-GATE` | Blocked on an owner (product/legal/operational) decision | Forbids guessing behavior in code; waits for the formal decision |

---

## 2. Programmatic counters (regenerable — no hand-maintained numbers)

Measured on `38546731`; regenerate with the same commands before updating:

| Counter | Value | Command |
|---|---|---|
| Maven units in the root reactor | **29** | `grep -c '<module>' pom.xml` |
| Flyway migration files | **173** = 159 versioned `V*` + 3 repeatable `R__` + 11 `.conf` sidecars | `ls marketplace-app/src/main/resources/db/migration \| wc -l` |
| Versioned migration range (numeric) | **V1..V212** (max `V212__disputes_loan_idx_concurrently.sql`) | numeric sort of `V*.sql` basenames |
| Android Kotlin files | **14** | `find android -name '*.kt' \| wc -l` |
| Client Retrofit endpoints (`MarketplaceApi.kt`) | **39** methods (20 POST, 12 GET, 1 PUT, 6 DELETE) | `grep -cE '@(GET\|POST\|PUT\|PATCH\|DELETE)' MarketplaceApi.kt` |
| Server commerce endpoints | Cart **2**, Orders **5** (incl. `POST /{id}/payment-intent`), Payments **8** (incl. webhooks + refund) | count `@*Mapping` per controller |
| Commerce domain tests | orders **3**, payments **19**, booking **9**, lending **6**, disputes **4**, messaging **12**, reviews **12** | `find marketplace-<dom>/src/test -name '*.java' \| wc -l` |

**Client contract surface (the whole of it, 39 endpoints):** auth/register, email-verification/resend, passkey assertion+registration (Credential Manager), `oauth2/token` passkey grant, `api/v1/search` (single-leg listings), geo suggest/tree, neighborhood membership+verification, posts feed/search/create/lost-found-state, media upload/complete, comments, reactions, neighborhood market (get/create/withdraw), notifications list+mark-read, events+RSVP, groups join/leave, polls create/vote/withdraw. **Everything else in this matrix is `CLIENT-GAP`.**

---

## 3. The master journey matrix (J-001..J-032)

Codes combine sides with `+`; priorities P0 > P1 > P2.

| ID | Journey | Backend @38546731 | Android | Concrete gaps | Status | P |
|---|---|---|---|---|---|---|
| J-001 | App install & valid service access | readiness + OpenAPI + presence tests | Compose/Retrofit scaffold with error/loading | No unified client/server acceptance report on a real device | `ANDROID-PARTIAL + OPS-GATE` | P0 |
| J-002 | Register & direct passkey sign-in | SAS 7.1.1, WebAuthn/passkeys/OIDC; #548 | registration + Credential Manager screens wired | RP ID + APK key-hash operational config; browser AppAuth path coexists (D-P01) | `ANDROID-PARTIAL + OPS-GATE + DECISION-GATE` | P0 |
| J-003 | Session restore, logout, token expiry | Spring Session server-side invalidation on sign-out (Security 7.1.1 wave) | tokens memory-only (measured: `MainActivity.kt` comment "intentionally memory-only"); 15-min access token noted in code | Pin exact access-token TTL from authorization config by measurement; prove long-lived session restore & full data wipe | `ANDROID-PARTIAL + OPS-GATE` | P0 |
| J-004 | Location pick, geo, join neighborhood | membership + geo tree/suggest controllers | membership + verification wired (`/me/neighborhood`) | trusted-member recommendation link not wired to contract | `ANDROID-PARTIAL` | P0 |
| J-005 | Community feed & topical rows | posts feed + advanced search | `getFeed()` connected | no unified cross-domain topical row aggregation | `ANDROID-PARTIAL + DOMAIN-GAP` | P0/P1 |
| J-006 | Posts, media, comments, reactions | posts/comments/media/lost-found controllers | create post, 5-image upload, comments, reactions wired | prove idempotency on retry; notification deep-links | `ANDROID-PARTIAL` | P0 |
| J-007 | Local questions/requests to resolution | QUESTION/REQUEST post types server-side | screens cover base types | no OPEN→RESOLVED request lifecycle loop | `ANDROID-PARTIAL + DOMAIN-GAP` | P1 |
| J-008 | Groups, events, polls | controllers + tests (community) | join/RSVP/vote wired | reminders, preferences, cancel flows, notification deep-links unproven | `ANDROID-PARTIAL` | P1 |
| J-009 | Neighborhood market (used goods) | market controller + lifecycle | get/create/withdraw wired | photo persistence proof, safe contact, sold/expired states | `ANDROID-PARTIAL` | P1 |
| J-010 | Paid lending & disputes | `LendingController` (6 tests) + ADR-0009 dispute machine | **zero lending contracts** | request→pay→deliver→return→dispute not in client | `CLIENT-GAP` | P1/P2 |
| J-011 | Direct messaging | `MessagingController` (12 tests) | **zero messaging contracts/screens** | start-from-post/listing, inbox, block | `CLIENT-GAP` | P0/P1 |
| J-012 | Notification center & push | phase-7 routing engine (#545), 25 types (V206), FCM HTTP v1 | list + mark-read only | **no FCM token registration/rotation in client**; no deep navigation | `ANDROID-PARTIAL + OPS-GATE` | P0/P1 |
| J-013 | Reports & content appeals | moderation surfaces server-side (`ReviewModerationAdminController` …) | **zero report contracts** | report reasons, hide-on-duplicate, outcome visibility | `CLIENT-GAP` | P0/P1 |
| J-014 | Official alerts & urgent notices | `UrgentAlertController` + admin (V154 module, 4 controllers) | **zero alert contracts** | authorization, time window, withdrawal, 403 proofs | `CLIENT-GAP + OPS-GATE` | P0 |
| J-015 | Neighborhood knowledge wiki | `KnowledgeController` (V156) | **zero knowledge contracts** | browse/contribute/review loop not in client | `CLIENT-GAP` | P1 |
| J-016 | Local news from accredited sources | `NewsController` + `NewsAdminController` | **zero news contracts** | publisher accreditation + attribution rights decision open | `CLIENT-GAP + DECISION-GATE` | P1 |
| J-017 | Unified multi-domain search | unified search engine phase-5 (#544) + Arabic corpus (ADR-0007) | single-leg `searchListings` only | no client surface searching products+business+jobs together | `ANDROID-PARTIAL + DOMAIN-GAP` | P1 |
| J-018 | Listing details, media, inquiry | realestate filters + saved records | filter search wired | rich details/map/media/contact screen not proven | `ANDROID-PARTIAL` | P1 |
| J-019 | Stay/service booking with slots | `BookingController` (9 tests) + pricing/availability | **zero booking contracts** | guest/host flows, cancellation/refund policy | `CLIENT-GAP + DOMAIN-GAP` | P0/P1 |
| J-020 | Business profile & hours | provider + public business page | provider name/rating only in lists | search-result → full business page with services | `ANDROID-PARTIAL` | P0/P1 |
| J-021 | Qualified review & owner reply | `ReviewsController` (12 tests, edit/reply/vote) | **zero review contracts** | tie review to confirmed transaction; separate recommendation vs review (D-P08) | `CLIENT-GAP` | P1 |
| J-022 | Merchant onboarding & management | provider files + verification + services | **zero merchant console screens** | ownership proof, hours/services editing from client | `CLIENT-GAP` | P1 |
| J-023 | Browse store product catalog | storefront + Q&A (B-16/C.8, V170, #538); catalog module | **zero catalog browse contracts** — market tab honestly labels itself "separate from the stores catalog" (measured `DiscoveryScreens.kt:61`) | no public, sortable, categorized product listing path | `CLIENT-GAP + API-GAP` | P0 |
| J-024 | Store setup & inventory & offers | `V116__store_root_product.sql`; `Product.providerId` ownership | **zero store corridor in client** | Provider↔Store identity decision (D-P03) before seller screens | `BE-PRESENT + CLIENT-GAP + DECISION-GATE` | P0 |
| J-025 | Cart & quantities & pricing | `CartController` (2 endpoints, create-on-read, `V113__orders_cart_tables.sql`, 3 tests) | **zero cart contracts/screens** | quantity edit, price/fee verification surface | `BE-PROVEN + CLIENT-GAP` | P0 |
| J-026 | Multi-seller checkout & payment | `OrdersController` (5 endpoints incl. payment-intent) + `PaymentsController` (8 endpoints) + ADR-0010 atomic split | **zero checkout/order/payment contracts** | `additionalOrders` consumption, 409 payment errors, currency policy (D-P04) | `CLIENT-GAP + OPS-GATE` | P0 |
| J-027 | Fulfillment, delivery, tracking | order state machine PLACED→…; payment events | **zero fulfillment screens** | delivery model decision open (D-P05) — no fake tracking allowed | `DOMAIN-GAP + CLIENT-GAP` | P0 |
| J-028 | Jobs & vehicles & specialized | `JobsController` + verticals independent | **zero jobs/vehicles journeys** | jobs/vehicles must not be modeled as generic store products | `CLIENT-GAP + DOMAIN-GAP` | P1 |
| J-029 | Switch between multi-role capacities | identity phase-1 multi-role + trust vocabulary (#543, V187-V189) landed **after** the base study commit | profile has no capacity switch | active-capacity context design; server-authorized elevation only | `ANDROID-PARTIAL + DOMAIN-GAP` | P1 |
| J-030 | Operational console & appearance | `marketplace-console` (V157/V201) + admin paths | **zero in-app console** | safe surface, preview+confirm for sensitive ops (D-P07) | `BE-PRESENT + CLIENT-GAP` | P1/P2 |
| J-031 | Arabic/RTL, accessibility, screens | unified error contract; Arabic+RTL in app | RTL renders; scaling constraints | 320px + TalkBack acceptance proof | `ANDROID-PARTIAL + OPS-GATE` | P0 |
| J-032 | Stability, tracing, recovery | health/metrics/event tests; watchdog smoke | no client crash/perf binding | one acceptance report binding commit↔environment↔performance | `BE-PRESENT + OPS-GATE` | P0 |

**At base `38546731` no journey holds `E2E-PASS`.** That is the honest headline of M0.

---

## 4. Locked architectural decisions (reused verbatim — no redesign)

| Decision | Locked fact | Effect on the matrix |
|---|---|---|
| ADR-0007 | Arabic search via `pg_catalog` programmatic config + PostgreSQL full-text indexes | No parallel Arabic DDL; client search binds to the approved path only |
| ADR-0008 | PostgreSQL is the only search engine until formal baseline measurements (D-07 gate) | No external search engine/vector duplication before the gate |
| ADR-0009 | Lending dispute state machine + late fees over the ledger | Client binds to this machine; no parallel finance model |
| ADR-0010 | One cart splits atomically into one order per seller with reservation | Client must consume `additionalOrders` in checkout |
| Module ownership | catalog owns products, orders owns cart+orders, payments owns payment, booking owns booking | **No** `marketplace-commerce` parallel unit — the unified plan's D-U4 is superseded by domain ownership |

## 5. Open decisions requiring owner resolution

| ID | Subject | Evidence-based recommendation | Release condition |
|---|---|---|---|
| D-P01 | Android auth path | Credential Manager + passkey direct; the browser AppAuth path is a **product decision**, not a compliance violation (#554 just fixed its request-preservation) | Direct sign-in proven on a documented device |
| D-P02 | Session & token revocation | Distinguish server session vs local confirmation; pin token TTL by measurement | Proven expiry + storage wipe on explicit logout |
| D-P03 | Provider↔Store relationship | `Product.providerId` as-is; no duplicate store entity without domain justification | Catalog gap analysis + store identity unified |
| D-P04 | Currency policy | No silent SAR fallback; explicit currency per offer | Payment/refund test per supported currency |
| D-P05 | Local delivery model | Pickup vs store courier; per-shipment operational path | No tracking UI without a real data source |
| D-P06 | Stays booking scope | Distinguish direct stays vs long-term lease; align fields with `marketplace-booking` | No fake instant-book UI |
| D-P07 | In-app operational control | Build verified admin surface in the approved app; no executable code via settings | Preview + auditable confirm |
| D-P08 | Recommendation vs review | Recommendation = trust signal; review = confirmed transaction | Clear badges, no mixing |
| D-P09 | MVP scope | Pick critical journeys for launch; others stay recorded and owned | No "platform complete" claims from partial readiness |

## 6. Readiness gates (G1–G6, no exceptions)

| Gate | Requirement |
|---|---|
| **G1 Contract & ownership** | Every screen carries a matrix ID (J-xxx), owning domain, exact API points, OpenAPI object match |
| **G2 Scenario coverage** | Happy path + token expiry + network loss + idempotent retry + geo constraint tests |
| **G3 Android proof** | Compose screens proven on real devices/emulators; RTL; 320px; TalkBack |
| **G4 Security & privacy** | Server-side authorization on every read/write; no contact/geo-privacy leaks in public responses |
| **G5 Money & inventory** | Server-side pricing only (no floats); ADR-0010 atomic split; atomic reservation |
| **G6 Operational range** | Builds + Flyway clean; rehearsed rollback; staging alerts mirroring production |

## 7. Execution waves

| Wave | Scope (journeys) | Entry condition | Acceptance output | Blockers |
|---|---|---|---|---|
| **M0** | Reference unification (this wave) | repo protocol; OpenAPI extraction; model head | truth log updated; unified matrix live | docs↔code divergence or migration conflict |
| **M1** | Accounts & sessions & geo (J-001..J-004) | identity contracts stable | direct sign-in, session restore, neighborhood pick | AppAuth/browser path unresolved (D-P01) |
| **M2** | Community core (J-005..J-008) | M1 stable | post/feed/interact/notify without duplicates | removed content reappearing; comment dup on retry |
| **M3** | Discovery & trust & business (J-013..J-018, J-020) | domain IDs pinned | search→asset→contact flow | duplicate business records; cross-neighborhood leaks |
| **M4** | Stores & commerce (J-023..J-026) | D-P03/D-P04 resolved | browse→details→cart→pay→confirm | no public product list; split failure |
| **M5** | Fulfillment & merchant console (J-027) | D-P05 resolved; order contracts stable | seller order view + state updates | fake delivery states; non-owner mutations |
| **M6** | Stays & jobs & lending (J-010, J-019, J-028) | per-vertical gap analysis | full lifecycle per vertical | booking/commerce/lending mixing |
| **M7** | Alerts & messaging & roles & console (J-011, J-012, J-029, J-030) | institution authorization docs | routing, appeals, capacity switch | FCM token leaks; console privilege escalation |
| **M8** | Stability & devices & production (J-031, J-032) | all P0 ready for ops testing | unified acceptance report commit↔env↔perf | unhandled crashes; rollback failure |

## 8. Evidence log schema (mandatory for any `E2E-PASS` promotion)

`journey_id`, `user_outcome`, `actors_roles`, `source_of_truth` (owning entity/table/domain), `android_entry` (screen/composable/viewmodel), `api_contract` (path + OpenAPI version + error codes), `data_events` (tables + published/consumed events), `positive_test` (test name + result + commit), `negative_tests` (failure/authorization/duplicate evidence), `device_proof` (device/build/OS/scenario), `operation_gate` (env readiness without secrets), `status` (pentagonal codes), `release_owner` (signature + date + verdict).

## 9. Maintenance law

1. A status flips **only** in the same merge batch that lands its evidence — mirror of `docs/unified-platform-plan.md` §9 convention.
2. Counters regenerate by command; hand-written numbers are forbidden.
3. `E2E-PASS` requires the full §8 log; anything less stays a lower code.
4. This matrix never contradicts the official docs; where official docs govern technology, they win; where this matrix governs journey state, its measured codes win over every other internal document's prose.
