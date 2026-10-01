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
  backend (Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1, 22 Maven
  modules, Flyway, PostgreSQL/PostGIS + Redis, Railway
  production/staging/edge). Use it for ANY request touching this
  backend — app-java-v3, the backend team's repo, a module
  (catalog/booking/payments/identity/community/...), Flyway migrations,
  the OpenAPI contract, a backend PR or review wave, Railway backend
  services — including bare Arabic continuations such as «ادمج»
  (merge), «راجع» (review), «تابع» (continue), «ما وضع الباك اند؟»
  (status). Encodes: AGENTS.md §0 enforced governance (the mandatory
  load order + the five-field declaration before any mutating action);
  the METHODOLOGY.md 6-stage workflow as the method; GitHub as the only
  truth (zero-memory bootstrap); word-gated merges («ادمج» through the
  three measured gates — CI green + CodeRabbit success + zero open
  threads); measurement governs with dated evidence «ملف:سطر» and the
  environment outranking doc snapshots; no Docker locally so
  integration tests judge on CI. Replies to the owner are always
  Arabic; the machine layer (code, commits, technical docs) is English.
---

# app-java-v3 backend agent — the bootstrap map

The complete authority is `docs/agent-protocol.md` in the repo. This
skill boots you into it. On any disagreement, the twin governs and
this skill is patched to match.

## 1. Session bootstrap (in order, every session)

1. Re-sync the clone ff-only (`git fetch origin && git checkout main
   && git merge --ff-only origin/main`); missing clone → re-clone from
   GitHub. The only local assumption is GitHub credentials (ask the
   owner when missing).
2. Read `AGENTS.md` — §0 is ENFORCED: the mandatory load order
   (SYSTEM.md → PROJECT_MAP.md → the task's governing file) and the
   five-field declaration before any mutating action.
3. Read `docs/agent-protocol.md` (the full method), then the root
   family it binds: `SYSTEM.md` (mechanism, facts with file:line
   evidence), `PROJECT_MAP.md` (state — newest wave sections first),
   `docs/METHODOLOGY.md` (the master 6-stage method),
   `CONTRIBUTING.md` (channels, contract rules, secrets policy).
4. Check in-flight work: `git log --oneline -15` + open PRs/issues via
   the API. Open PRs are forward-contract risk (OpenAPI changes ripple
   to waelhe/web-marketplace).
5. Toolchain: Temurin JDK 25 + `./mvnw` (wrapper 3.9.16). No Docker
   locally — integration tests judge on CI.
6. Measure before building: live `/v3/api-docs` on staging, success
   AND failure shapes of the target endpoints. Every fact carries its
   measurement date.

## 2. The five operating rules (condensed — full text in the twin §4)

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

## 3. Standing facts (the twin §2 table is the authority)

- Java 25 / Spring Boot 4.1.1 / Modulith 2.1.1 / SAS 7.1.1 / Maven
  wrapper 3.9.16; 22 modules in the reactor.
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
