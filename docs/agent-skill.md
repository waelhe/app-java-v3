# The derivable bootstrap skill — `app-java-v3-backend-agent`

> This file is the **skill spec** referenced by
> [`docs/agent-protocol.md`](./agent-protocol.md) §11: the packaging of
> the backend-agent protocol for installation into any agent system's
> skills mechanism (opencode, Claude, Cursor-style rules, or any
> framework that loads skill files). The protocol twin governs; this
> spec is the copy-paste-able bootstrap map. Provenance: same as the
> twin (owner's one-time exception, 2026-09-28; transferred from the
> frontend `marketplace-platform-agent` skill, A/B-validated with six
> zero-context agents).

## Install

Copy the fenced block below verbatim into your agent system's skills
directory as `app-java-v3-backend-agent/SKILL.md` (adjust only the
leading path conventions of your system). Nothing else is needed — the
skill's references are all in this repo. After any amendment to
`docs/agent-protocol.md`, re-sync the skill (the twin governs).

## The SKILL.md (verbatim)

````markdown
---
name: app-java-v3-backend-agent
description: >-
  Durable operating protocol for the waelhe/app-java-v3 marketplace
  backend (Java 25, Spring Boot 4.1.2, Spring Modulith 2.1.2, 30 Maven
  modules, Flyway, PostgreSQL/PostGIS + Redis, Railway
  production/staging/edge). Use it for ANY request touching this
  backend — app-java-v3, the backend team's repo, a module
  (catalog/booking/payments/identity/community/...), Flyway migrations,
  the OpenAPI contract, a backend PR or review wave, Railway backend
  services — including bare Arabic continuations such as «ادمج»
  (merge), «راجع» (review), «تابع» (continue), «ما وضع الباك اند؟»
  (status). Encodes: AGENTS.md §0 enforced governance (the mandatory
  load order + the six-field declaration before any mutating action);
  the METHODOLOGY.md 6-stage workflow as the method; GitHub as the only
  truth (zero-memory bootstrap); official version-matched documentation
  and source repositories govern all technologies; no unsupported manual
  workarounds; load only task-relevant pinned skills; GitHub-first branch
  and PR execution; do not idle while independent CI/review work runs;
  word-gated merges («ادمج») require all remote gates. Replies to the
  owner are always Arabic; the machine layer (code, commits, technical
  docs) is English.
---

# app-java-v3 backend agent — the bootstrap map

The complete authority is `docs/agent-protocol.md` in the repo. This
skill boots you into it. On any disagreement, the twin governs and
this skill is patched to match.

## 1. Session bootstrap (in order, every session)

1. Read current repository state and the default-branch SHA through
   GitHub. A local clone is optional supplementary tooling, never the
   primary execution surface.
2. Check in-flight work through GitHub, including all open PRs/issues
   and recent commits. Open PRs are forward-contract risk (OpenAPI changes
   ripple to waelhe/web-marketplace).
3. Read `AGENTS.md` — §0 is ENFORCED: the mandatory load order
   (SYSTEM.md → PROJECT_MAP.md → the task's governing file) and the
   six-field declaration before any mutating action.
4. Read `docs/agent-protocol.md` (the full method), then the root
   family it binds: `SYSTEM.md` (mechanism, facts with file:line
   evidence), `PROJECT_MAP.md` (state — newest wave sections first),
   `docs/METHODOLOGY.md` (the master 6-stage method),
   `CONTRIBUTING.md` (channels, contract rules, secrets policy).
5. Defect-knowledge system (`docs/governance/defect-knowledge/` —
   protocol §9's regression list is its quick view; protocol §3's
   step 4 is the binding ritual step this item mirrors): read its
   `SKILL.md` router first, then load only the focused `rules/*.md`
   packs triggered by the change (money, events, authorization,
   contracts, database/concurrency, configuration/release, modes, or
   signal triage). Do not load every pack for an unrelated task.
   Before editing, load `change-protocol.md` and apply the relevant
   patterns P1–P14; before fixing, inspect evidence E-01…E-51 and the
   A/B/C/D rules in `rulebook.md`; after implementing, run the
   adversarial pass in `review-protocol.md`. For each new code/runtime
   PR, create `change-records/PR-<number>.json` and run D11; a fixed
   claim requires a reachable repair commit and an API-verified
   successful workflow run on the exact PR head. Review intake captures
   comments as untrusted signals; adjudicate each material source item.
   `SKILL.md` is a routing guide, not a second rulebook.
6. Read `docs/governance/agent-skills-policy.md` and
   `docs/governance/plans/agent-skills-pins.json`; load only relevant,
   pinned `.agents/skills/<name>/SKILL.md` files and their triggered
   rules. Skills are references, not authorities; never run an unpinned
   skill installer or `npx skills add` in this repository. For Saleor
   tasks, start at https://docs.saleor.io/developer/ai and `llms.txt`,
   use its Documentation MCP where available, use the schema matching
   the repository's pinned Saleor release, and load the adopted
   `saleor-core` skill when its covered behavior applies. Other Saleor
   skills require an actual need and reviewed adoption under the policy.
7. Local JDK/Maven execution is optional supplementary feedback; do not
   block GitHub-first work on provisioning a local toolchain. Remote
   GitHub Actions are the required verification record. Docker-dependent
   integration tests run through their configured remote workflow when
   local Docker is unavailable.
8. Measure before building: live `/v3/api-docs` on staging, success
   AND failure shapes of the target endpoints. Every fact carries its
   measurement date.

9. Emit the AGENTS.md §0.2 declaration before any mutating action
   (`[الملف الحاكم]` / `[النواة المعمارية]` / `[البند من المصفوفة §14.2]` /
   `[أثر على الحدود]` / `[دين]` / `[معرفة العيوب]`) — the twin §3 step 8,
   verbatim in force.
10. Create the remote task branch from the exact observed base SHA
    and make durable edits/commits on GitHub; open a PR for review. Do
    not push directly to `main` (the twin §3 step 9).
## 2. The seven operating rules (condensed — full text in the twin §4 and §12)

1. Definition before building — the governing file + SYSTEM.md §14.2
   matrix decide, never an endpoint that happens to exist.
2. The METHODOLOGY 6-stage workflow is the method; skipping stages is
   a forbidden process pattern.
3. Full execution authority on the branch; the merge to main ONLY on
   the owner's explicit word («ادمج») through the three measured gates
   (CI green + CodeRabbit success + zero open threads +
   mergeable/CLEAN). The merge IS the production deploy trigger
   (Railway auto-deploy).
4. The gates are sacred: CI suite (gitleaks → OpenAPI compat → build →
   unit + integration), JaCoCo ≥ 70% per module, Modulith
   verification, the mandatory PR template, one approval, no
   unresolved comments. Regression pins must FAIL on the previous
   code. Never merge red.
5. Measurement governs: evidence `ملف:سطر`, dated facts + re-verify
   commands (R1), the environment outranks the doc snapshot; when live
   verification is impossible — stop and declare, never guess.
6. No unsupported manual implementation: use documented automatic
   mechanisms and supported extension/configuration points across every
   technology; do not invent workarounds or blindly delete supported
   customizations.
7. Do not idle for pending CI/CodeRabbit: continue independent work on
   non-conflicting branches and monitor results; pending is not passing.
   For applicable PRs request `@coderabbitai review`, check current-head
   coverage, and respect the repository's >100-file fail-closed review
   coverage process. Never bypass a gate or merge without the owner's
   explicit word.

## 3. Standing facts (the twin §2 table is the authority)

- Java 25 / Spring Boot 4.1.2 / Modulith 2.1.2 / SAS 7.1.2 / Maven
  Maven distribution 3.10.0 (wrapper tool 3.3.4); 30 modules in the reactor.
- PostgreSQL 18 + PostGIS (production DB = external Neon), Redis 8,
  Flyway (ddl-auto: none; new V{N} migrations only, never edit
  existing).
- OpenAPI at `/v3/api-docs` (re-measure live; CONTRIBUTING.md §4 holds
  the re-verification command).
- Production `app-java-v3-production.up.railway.app`, staging
  `app-java-v3-staging-staging.up.railway.app`, Railway auto-deploy
  from main on every merge.
- Secrets live in Railway only (rotate); gitleaks guards the repo;
  security disclosures via GitHub Private Vulnerability Reporting,
  never public issues.

## 4. Language policy (binding)

English = machine layer (code, commit titles, technical docs). Arabic =
owner layer: every reply to the owner is Arabic; PR bodies are Arabic
with measured numbers. The owner's literal words («ادمج», standing
rules) are quoted verbatim with an English gloss — data, not prose.
````

## Sync rule

The twin (`docs/agent-protocol.md`) governs. After amending it, update
this spec in the same PR so the two never drift (the frontend system's
measured lesson: a drifted skill re-introduces retired facts — the
stale-URL regression found by the A/B test there).
