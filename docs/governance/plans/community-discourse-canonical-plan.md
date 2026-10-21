# Community Canonical Contract Plan — Discourse reference (D-COMMUNITY-DISCOURSE-001)

**Status:** Adopted by owner word on 2026-10-21 ("Discourse (موصى به)") after a multi-candidate measurement round.
**Harness decision:** `D-COMMUNITY-DISCOURSE-001`. Companion commerce decision remains `D-SALEOR-FULL-001` — the two plans are independent; neither supersedes the other.

## 1. What this plan is (and is not)

Sole governing document for the **community wire contract reference**: which mature platform's semantics the community surface must match, how capabilities are inventoried, who owns each capability, and how divergences are registered.

- It is a **contract reference plan**, not a feature-execution plan. Feature work, edge cases and acceptance journeys continue to live in `docs/neighborhood-community-plan.md` (unchanged, still governing for features).
- The backend does **not** run Discourse (no second community engine), exactly as `D-SALEOR-FULL-001` refuses a second commerce engine. Discourse is the pinned **semantic + contract reference** (entity model, moderation workflow, permission vocabulary, capability semantics) that our Spring Boot + DGS + Modulith owners implement.
- It governs **capability semantics for community only** (posts, comments, reactions, polls, events, groups, moderation, trust). Commerce ( Saleor contract) and auth (auth redesign plan) keep their own authorities.

## 2. Source of truth and pins

| Pin | Value | Measured at |
|---|---|---|
| Pinned reference tag | `discourse/discourse` **`v2026.9.0`**, commit `343b20f97ef5f6bd70826ee0e993ba348b210fd5` | 2026-10-21 (official tags API) |
| Latest observed publish marker | `v2026.10.0-latest`, commit `a51251632c2dae3e0be8e0ba9119dc295b838128` | 2026-10-21 (official tags API) |
| Official API reference | https://docs.discourse.org/ (Discourse REST API docs) | linked 2026-10-21 |
| Bundled plugins listing | `plugins/` folder of `discourse/discourse@main` (`a512516…`) measured 2026-10-21 | must be re-measured **on the pinned tag** at each wave start |

Version policy: like `saleor-source-pins.json`, pins are updated only via a PR whose body carries the fresh measurement (tag + commit SHA + URL). No automatic pin bumps. i.e. drift guard tooling is a **follow-up**: adding `.ci` checkers and `.github/workflows/` files requires the workflow-review protocol first and is intentionally **not** part of this PR (recorded as `ADV-COMM-001`).

## 3. Structural difference verified up front (D-COMM-006)

Discourse's published contract is a **REST** surface; our wire is **GraphQL (DGS)**. Therefore the community contract is adopted as a **semantic reference** (entities, states, permissions, moderation flow), and each capability is translated to our GraphQL surface with its own measured signatures. Exact-name parity with REST endpoints is **not** the completion measure; completed = implemented owner delegation + validation + authorization + persistence/read-back + tests, same honesty rules as §5 of the commercial plan.

## 4. Ownership rules (collision prevention, mirrored from the commercial playbook)

1. **One owner per capability** — rows in `community-discourse-capability-matrix.csv` name the owning module/artifact. No duplicate stores (no second community engine, no direct cross-module persistence access).
2. **Governance files are steward-owned** (`community-*` CSVs and this plan): community agents submit proposed row updates; they do not race-edit them.
3. **Divergences must be registered**, never silent: any semantic/adoption difference lands in `community-contract-divergences.csv` with an owner and tests. A release-blocking divergence cannot be waved through as "future work".
4. **No fake/no-op surfaces**: a capability is not complete because a REST endpoint name or GraphQL field exists (same rule as Saleor plan §5).
5. Platform-specific extensions (neighborhood geography, community market, lost & found, sale bridging) are **inventory-registered as extensions**, never counted as Discourse parity — identical to the "legacy roots separated" rule of the commercial plan.

## 5. Capability matrix and statuses

Matrix: `docs/governance/plans/community-discourse-capability-matrix.csv` (17 initial rows measured from repo truth on 2026-10-21).
Statuses: `IMPLEMENTED` · `PARTIAL` · `IMPLEMENTATION_REQUIRED` · `NEEDS_ADR` · `EXCLUDED_EXTENSION`.
Overall progress is read from the matrix, never from prose. Wave discounts: `NONE` — waves w1..w9 below each own a bounded row set.

| Wave | Scope |
|---|---|
| w1 (this PR) | governance set: plan + matrix + divergences + pins |
| w2 | topics/posts/comments contract rows to GraphQL wire (owner: community posts/comments) |
| w3 | polls contract alignment (plugin `poll` reference) |
| w4 | events + RSVP alignment (plugin `discourse-events` reference) |
| w5 | reactions alignment (plugin `discourse-reactions` reference) |
| w6 | moderation flags queue + trust-level vocabulary alignment |
| w7 | groups/categories/membership semantics + ADR for category zoning |
| w8 | messaging boundary shot-call (bundled `chat` vs `marketplace-messaging`) |
| w9 | admin surface + notifications/search/media cross-module rows |

## 6. Non-goals

- Running Discourse or its Postgres; importing its Ruby codebase.
- Counting any "N roots" as completion (no such total is claimed anywhere in this plan).
- Changing `.github/workflows/` in this PR (workflow-review protocol required; `ADV-COMM-001`).
- Touching the commerce (Saleor) governance set.
