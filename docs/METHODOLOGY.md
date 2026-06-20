# Methodology & Protocol — Unified Development Standard

> **Status:** Living document • Last updated: 2026-06-20
> **Audience:** All developers (including AI agents) contributing to app-java-v3
> **Scope:** End-to-end methodology for planning, executing, reviewing, and releasing work
>
> **This is the master protocol.** All other docs (CODING_STANDARDS.md, ARCHITECTURE.md,
> governance/*, release/*, security/*) are specialized extensions of this document.
> When in doubt, this document is the final authority.

---

## Table of Contents

1. [Core Principles](#1-core-principles)
2. [The 6-Stage Workflow](#2-the-6-stage-workflow)
3. [Stage 1 — Research First](#3-stage-1--research-first)
4. [Stage 2 — Verify Reality](#4-stage-2--verify-reality)
5. [Stage 3 — Establish Baseline](#5-stage-3--establish-baseline)
6. [Stage 4 — Implement Cleanly](#6-stage-4--implement-cleanly)
7. [Stage 5 — Verify No Regression](#7-stage-5--verify-no-regression)
8. [Stage 6 — Document & Ship](#8-stage-6--document--ship)
9. [Sprint Framework](#9-sprint-framework)
10. [Maven Build Protocol](#10-maven-build-protocol)
11. [PR Protocol](#11-pr-protocol)
12. [Review Protocol](#12-review-protocol)
13. [Release Protocol](#13-release-protocol)
14. [Emergency Fix Protocol](#14-emergency-fix-protocol)
15. [Debt Management Protocol](#15-debt-management-protocol)
16. [Forbidden Patterns](#16-forbidden-patterns)
17. [Decision Log](#17-decision-log)

---

## 1. Core Principles

These principles are non-negotiable. Every action in this repository must align with them.

### 1.1 Official Documentation is the Source of Truth

> **Rule:** Every code change, configuration change, and architectural decision must cite
> an official reference. No exceptions.

**Approved sources (in priority order):**

| Priority | Source | URL |
|----------|--------|-----|
| 1 | Spring Boot Reference | https://docs.spring.io/spring-boot/ |
| 2 | Spring Boot How-To (Deployment) | https://docs.spring.io/spring-boot/how-to/deployment/index.html |
| 3 | Spring Security Reference | https://docs.spring.io/spring-security/reference/ |
| 4 | Spring Security What's New | https://docs.spring.io/spring-security/reference/whats-new.html |
| 5 | Spring Modulith Reference | https://docs.spring.io/spring-modulith/ |
| 6 | Spring Cloud Gateway | https://spring.io/projects/spring-cloud-gateway |
| 7 | Spring Guides | https://spring.io/guides |
| 8 | Maven Guides | https://maven.apache.org/guides/index.html |
| 9 | RFCs (6238, 6749, 7807, 8252, 9068, 6797, 7009) | https://datatracker.ietf.org/ |
| 10 | NIST (SP 800-63B, SP 800-57) | https://pages.nist.gov/800-63-3/ |
| 11 | OWASP Cheat Sheets | https://cheatsheetseries.owasp.org/ |

**If an action cannot cite one of these sources, it must not be performed.**

### 1.2 Verify Before Acting

> **Rule:** Never trust an unverified claim — including your own previous assumptions.
> Always read the actual code before modifying it.

### 1.3 Do No Harm

> **Rule:** No regressions. A change that breaks existing functionality is worse than no change.
> If a fix introduces new failures, it must be reverted or amended before merge.

### 1.4 Document or Don't Do It

> **Rule:** If it isn't documented, it doesn't exist. Every convention, decision, and pattern
> must be recorded in the appropriate doc.

### 1.5 Small, Reversible Steps

> **Rule:** One concern per PR. One PR per branch. If a change touches more than one concern,
> split it.

---

## 2. The 6-Stage Workflow

Every task — whether a feature, a fix, a refactor, or documentation — follows this workflow:

```
┌─────────────────────────────────────────────────────────────┐
│  Stage 1: RESEARCH FIRST                                    │
│  Read official docs. Fetch verbatim quotes. No coding yet.  │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Stage 2: VERIFY REALITY                                    │
│  Read the actual code. Confirm the problem exists.          │
│  Do not trust reports — verify with your own eyes.          │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Stage 3: ESTABLISH BASELINE                                │
│  Run tests. Record pass/fail counts. This is your safety    │
│  net — any regression after your change will be visible.    │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
┌──────────────────────────┬──────────────────────────────────┐
│  Stage 4: IMPLEMENT CLEANLY                                 │
│  Write the change following CODING_STANDARDS.md.            │
│  Cite the official doc in Javadoc/commit message.           │
│  No shortcuts, no TODOs, no @SuppressWarnings.              │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Stage 5: VERIFY NO REGRESSION                              │
│  Run the same tests from Stage 3.                           │
│  Compare counts. Delta must be 0 (or positive).             │
│  Run JaCoCo check. Run Modulith verification.               │
└──────────────────────────┬──────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────┐
│  Stage 6: DOCUMENT & SHIP                                   │
│  Update worklog.md. Write commit message. Push branch.      │
│  Create PR with full context.                               │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Stage 1 — Research First

### 3.1 What to do

Before writing any code or modifying any file:

1. **Identify which official doc covers the topic.** Use the source table in §1.1.
2. **Fetch the doc.** Use `curl` + `html2text` or a research subagent.
3. **Extract verbatim quotes** of the relevant section (with `>` blockquotes).
4. **Record the exact URL** for citation later.

### 3.2 What NOT to do

- ❌ Do not guess based on memory
- ❌ Do not rely on blog posts or Stack Overflow as primary sources
- ❌ Do not skip this stage "because I know it" — verify anyway
- ❌ Do not proceed to Stage 2 until you have a verbatim quote in hand

### 3.3 Example

```
Task: Fix "catch(Exception) in AvailabilityService defeats retry"

Stage 1 output:
- Official doc: https://docs.spring.io/spring-modulith/reference/events.html
- Verbatim quote:
  > "Each transactional event listener is wrapped into an aspect that marks
  > that log entry as completed if the execution of the listener succeeds.
  > In case the listener fails, the log entry stays untouched so that retry
  > mechanisms can be deployed."
- Key insight: catch(Exception) → listener "succeeds" → log marked COMPLETED → no retry
```

---

## 4. Stage 2 — Verify Reality

### 4.1 What to do

1. **Read the actual code.** Use `Read` tool, `grep`, `find`.
2. **Confirm the problem exists.** A report may be stale, wrong, or already fixed.
3. **Identify the exact location** (file + line number).
4. **Check for related occurrences** — the same pattern may exist elsewhere.

### 4.2 Verification commands

```bash
# Search for a pattern across all modules
grep -rn "pattern" --include="*.java" marketplace-*/src/

# Read a specific file
cat marketplace-*/src/main/java/.../File.java

# Check git history for recent changes
git log --oneline -10 -- path/to/file
```

### 4.3 What to record

- File path + line number
- Current code (before change)
- Why it's a problem (with the verbatim quote from Stage 1)

### 4.4 Honest disclosure

If the reported problem **does not exist** (already fixed, or was never there):
- ✅ Report this honestly
- ✅ Do not invent a fix for a non-problem
- ✅ Document the finding in worklog.md

**Example from PR #157:**
> "The report claimed Dockerfile uses `layertools` — verification showed it already
> uses `tools` (fixed in Sprint 0). No action needed."

---

## 5. Stage 3 — Establish Baseline

### 5.1 What to do

Before making any change, record the current state:

```bash
# Run tests for the module you'll touch
./mvnw -pl marketplace-<module> test 2>&1 | grep "Tests run:" | tail -5

# For cross-module changes, run all affected modules
./mvnw -pl marketplace-identity,marketplace-platform-infra test 2>&1 | grep "Tests run:" | tail -5

# Record the baseline
echo "BASELINE: <module> = N tests pass, JaCoCo met" >> /tmp/baseline.txt
```

### 5.2 Why this matters

- If your change breaks a test, you'll know **which test** and **when** it broke
- If a test was already failing before your change, you won't be blamed for it
- The delta (before vs after) is the only honest measure of your change's impact

### 5.3 Baseline record format

```
BASELINE (before change):
- marketplace-identity: 122 tests, 0 failures, JaCoCo met
- marketplace-platform-infra: 51 tests, 0 failures, JaCoCo met
- marketplace-app: 98 tests, 49 errors (PRE-EXISTING — Logback config error)
```

The "PRE-EXISTING" note is critical — it documents failures that exist before your change
so they're not attributed to you.

---

## 6. Stage 4 — Implement Cleanly

### 6.1 Before writing code

1. **Create a branch** from the correct base (usually `main`):
   ```bash
   git checkout main && git pull && git checkout -b <type>/<scope>
   ```
2. **Follow CODING_STANDARDS.md** for the specific pattern you're writing.
3. **Cite the official doc** in Javadoc or commit message.

### 6.2 Branch naming convention

| Type | Pattern | Example |
|------|---------|---------|
| Feature | `feat/<scope>` | `feat/image-upload` |
| Fix | `fix/<scope>` | `fix/dockerfile-layertools` |
| Refactor | `refactor/<scope>` | `refactor/remove-duplicate-bean` |
| Docs | `docs/<scope>` | `docs/coding-standards` |
| Chore | `chore/<scope>` | `chore/debt-cleanup` |
| Security | `security/<scope>` | `security/auth-hardening` |

### 6.3 Commit message format

```
<type>(<scope>): <short description>

<body explaining what + why + official doc reference>

<footer with PR number>
```

**Types:** `feat`, `fix`, `refactor`, `docs`, `chore`, `security`, `test`, `perf`

**Example:**
```
fix(availability): narrow catch(Exception) to DataAccessException for retry

The @ApplicationModuleListener onDayHasPassed delegates to
generateSlotsForDate, which wrapped each rule in catch (Exception e).
This swallowed all exceptions, causing the Spring Modulith event
publication aspect to see "success" and mark the log entry COMPLETED,
silently losing the event and defeating the retry mechanism.

Official doc (Spring Modulith Reference — The Event Publication Registry):
> "Each transactional event listener is wrapped into an aspect that marks
> that log entry as completed if the execution of the listener succeeds.
> In case the listener fails, the log entry stays untouched so that retry
> mechanisms can be deployed."

Fix: narrowed catch to org.springframework.dao.DataAccessException only.
- Data-access failures (constraint violations, deadlocks) are logged but
  do not abort the batch (best-effort per rule preserved)
- Programming errors (NPE, CCE) now propagate to the event publication
  log for retry

Verification: marketplace-availability 19 tests pass, JaCoCo met.

PR #157
```

### 6.4 Clean code rules (enforced)

- ❌ No `@SuppressWarnings` to silence warnings — fix the root cause
- ❌ No `TODO`, `FIXME`, `HACK`, `XXX` comments — finish the work or don't do it
- ❌ No `System.out` / `System.err` — use SLF4J logger
- ❌ No `e.printStackTrace()` — use `log.error("msg", e)`
- ❌ No `catch (Exception)` broad — narrow to specific exceptions
- ❌ No `ReflectionTestUtils.setField` for non-final fields — use constructor injection
- ❌ No `setAccessible(true)` on private methods — make method package-private
- ❌ No hardcoded secrets / URLs / ports in code
- ❌ No `@Autowired` field injection — use constructor injection
- ✅ Every fix cites its official reference in Javadoc or commit message

---

## 7. Stage 5 — Verify No Regression

### 7.1 Mandatory verification

After implementing, run **the same tests** from Stage 3:

```bash
# Run tests for affected module(s)
./mvnw -pl marketplace-<module> test 2>&1 | grep "Tests run:" | tail -5

# Run JaCoCo coverage check
./mvnw -pl marketplace-<module> verify -DskipITs 2>&1 | grep -i "jacoco\|coverage"

# For cross-module changes, run Modulith verification
./mvnw -pl marketplace-app test -Dtest=ModulithVerificationTest 2>&1 | tail -10
```

### 7.2 Delta comparison

Compare the results to the baseline from Stage 3:

```
BEFORE: marketplace-identity = 122 tests, 0 failures
AFTER:  marketplace-identity = 122 tests, 0 failures
DELTA:  0 (no regression) ✅
```

```
BEFORE: marketplace-app = 98 tests, 49 errors (PRE-EXISTING)
AFTER:  marketplace-app = 98 tests, 49 errors (same PRE-EXISTING)
DELTA:  0 (no regression, didn't fix pre-existing — out of scope) ✅
```

### 7.3 What to do if there's a regression

1. **Do not merge.**
2. **Investigate the root cause.**
3. **Fix the regression** (or revert your change if the fix is worse).
4. **Re-run verification** from scratch.
5. **Document what happened** in the commit message and PR body.

### 7.4 JaCoCo threshold

- Minimum coverage: **70%** (`jacoco.coverage.threshold=0.70` in `pom.xml`)
- Enforced on all domain modules via `jacoco:check` in `verify` phase
- If your change drops coverage below 70%, add tests or simplify the code
- **Never lower the threshold** to make the build pass — that's a debt

---

## 8. Stage 6 — Document & Ship

### 8.1 Update worklog

Append to `/home/z/my-project/worklog.md` using this template:

```markdown
---
Task ID: <unique-id>
Agent: <your name / agent name>
Task: <one-line description>

Work Log:
- <step 1>
- <step 2>
- <step 3>

Stage Summary:
- <key results>
- <PRs created>
- <official docs cited>
```

### 8.2 Commit and push

```bash
git add -A
git commit -m "<type>(<scope>): <description>

<detailed body with official doc reference>

Verification: <test counts + JaCoCo status>

PR #<number>"
```

```bash
git push -u origin <branch-name>
```

### 8.3 Create PR

Use the GitHub API or `gh` CLI. PR body must include:

- Summary of changes
- Official source(s) cited
- Verification results (test counts before/after)
- No-regression confirmation
- Link to related PRs (if any)

---

## 9. Sprint Framework

### 9.1 Sprint structure

| Element | Detail |
|---------|--------|
| Sprint length | 1-2 weeks (flexible) |
| Sprint goal | One clear objective (e.g., "image upload pipeline", "auth hardening") |
| Sprint planning | Define scope + acceptance criteria before starting |
| Sprint review | Demo working software at the end |
| Sprint retrospective | What worked / what didn't / what to change |

### 9.2 Sprint naming convention

```
Sprint <number> — <short goal>
```

**Examples from project history:**
- Sprint 0 — Dockerfile uses jarmode=tools
- Sprint 1 — File Storage module (P0)
- Sprint 2 — Deployment Optimization
- Sprint 3 — Unify @PreAuthorize, remove stale assumeTrue
- Sprint 4 — External services documentation
- Sprint 5 — AOT Cache evaluation

### 9.3 Priority levels

When multiple tasks compete, use this priority:

| Priority | Level | Example |
|----------|-------|---------|
| **P0** | Critical — production broken / security hole | Auth bypass, data loss |
| **P1** | High — broken feature, no workaround | Payment webhook fails |
| **P2** | Medium — degraded feature, workaround exists | Slow query |
| **P3** | Low — cosmetic / nice-to-have | Documentation typo |

### 9.4 Sprint checklist

Before starting a sprint:
- [ ] Goal is defined and documented
- [ ] Scope is realistic for the timebox
- [ ] Acceptance criteria are clear
- [ ] Dependencies are identified
- [ ] Risk register is reviewed

Before closing a sprint:
- [ ] All PRs merged or explicitly deferred
- [ ] Tests pass on `main`
- [ ] Worklog updated with sprint summary
- [ ] Next sprint goal proposed

---

## 10. Maven Build Protocol

### 10.1 Build lifecycle (mandatory)

> **Official doc:** https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html

```
validate → compile → test → package → integration-test → verify → install → deploy
```

### 10.2 Standard commands

```bash
# Full verification (the preferred command per Maven docs)
./mvnw verify

# Quick compile check for one module + dependencies
./mvnw compile -pl marketplace-<module> -am

# Run unit tests for one module
./mvnw test -pl marketplace-<module>

# Run integration tests only
./mvnw verify -DskipUnitTests

# Skip integration tests (faster feedback)
./mvnw verify -DskipITs
```

### 10.3 Quality gates (enforced in `verify` phase)

| Gate | Tool | Threshold |
|------|------|-----------|
| Compilation warnings | `maven-compiler-plugin` | `failOnWarning=true` (zero warnings) |
| Test coverage | `jacoco-maven-plugin` | ≥ 70% on all modules |
| Dependency convergence | `maven-enforcer-plugin` | No version conflicts |
| Upper-bound deps | `maven-enforcer-plugin` | No lower-version overrides |
| Duplicate POM deps | `maven-enforcer-plugin` | None |
| Maven version | `maven-enforcer-plugin` | ≥ 3.9 |
| Java version | `maven-enforcer-plugin` | ≥ 25 (target) |
| Modulith boundaries | `ModulithVerificationTest` | All modules verified |
| Secret scanning | `gitleaks` (CI) | High+ severity fails |

### 10.4 Multi-module reactor

> **Official doc:** https://maven.apache.org/guides/mini/guide-multiple-modules.html

The reactor sorts modules by dependency. When you build one module with `-am` (also make),
Maven builds all upstream dependencies first.

```bash
# Build marketplace-booking + all its dependencies
./mvnw verify -pl marketplace-booking -am
```

### 10.5 Dependency rules

1. **Do not pin versions** managed by Spring Boot BOM (let the BOM manage them)
2. **Do not add dependencies** unless officially documented and compatible with Boot 4.1
3. **Document exceptions** in `docs/clean-development-plan.md` Exceptions Registry
4. **Run `mvn dependency:tree`** to verify the dependency graph before adding a new dependency

---

## 11. PR Protocol

### 11.1 One concern per PR

- ✅ One feature, one fix, or one refactor per PR
- ❌ Do not mix features + fixes + refactors in the same PR
- If a change requires multiple concerns, split into multiple PRs

### 11.2 PR size guideline

- **Ideal:** < 300 lines changed
- **Acceptable:** < 1000 lines changed
- **Requires justification:** > 1000 lines changed (split if possible)

### 11.3 PR body template

```markdown
## Summary
<1-2 paragraphs explaining what + why>

## Official source(s) cited
- <URL 1 with section reference>
- <URL 2 with section reference>

## Changes
- <file 1>: <what changed>
- <file 2>: <what changed>

## Verification
| Module | Baseline | After | Delta |
|--------|----------|-------|-------|
| marketplace-X | N tests, 0 fail | N tests, 0 fail | 0 |

JaCoCo: All coverage checks have been met ✅
Modulith: Verification passed ✅

## No regression confirmation
<confirm that pre-existing failures are unchanged>

## Related PRs
- #<number>: <relationship>
```

### 11.4 PR review requirements

- At least one approval required
- All CI checks must pass
- No unresolved review comments
- Worklog updated

---

## 12. Review Protocol

### 12.1 What to review

1. **Official doc citation** — does the PR cite the right source?
2. **Coding standards compliance** — does it follow CODING_STANDARDS.md?
3. **No regression** — are test counts unchanged or improved?
4. **JaCoCo** — is coverage maintained or improved?
5. **Modulith boundaries** — does it respect module dependencies?
6. **No forbidden patterns** — check §16 of this document
7. **Commit message quality** — does it explain what + why + reference?

### 12.2 Review checklist

- [ ] Official doc cited in Javadoc/commit
- [ ] No `@SuppressWarnings`, `TODO`, `System.out`, `printStackTrace`
- [ ] No `catch (Exception)` broad (unless documented as fail-safe pattern)
- [ ] No `@Autowired` field injection (constructor injection only)
- [ ] No hardcoded secrets/URLs/ports
- [ ] Tests added/updated for new code
- [ ] JaCoCo ≥ 70% maintained
- [ ] Modulith boundaries respected
- [ ] PR body complete (summary, sources, verification, no-regression)

### 12.3 Review tone

- **Be specific:** cite line numbers and CODING_STANDARDS sections
- **Be constructive:** suggest the fix, don't just point out the problem
- **Be objective:** reference official docs, not personal preference
- **Be honest:** if you don't know, say so

---

## 13. Release Protocol

> **Detailed reference:** `docs/release/rollout-strategy.md`

### 13.1 Go / No-Go gates

A release is **Go** only when:
- [ ] CI is green on target branch
- [ ] All impacted module tests pass
- [ ] OpenAPI generation + validation pass
- [ ] DB migration review complete (if schema changed)
- [ ] Security review complete (if authn/authz changed)
- [ ] Rollback procedure prepared + owner assigned

### 13.2 Environment sequence

```
dev → staging → production canary (5%) → 25% → 50% → 100%
```

### 13.3 Rollback triggers

Rollback immediately if:
- Sustained 5xx error increase
- Critical flow failure (payment, booking, auth)
- Data integrity issue
- Security incident

### 13.4 Post-release

- Run smoke suite for critical APIs
- Verify OpenAPI docs endpoint
- Validate log/trace correlation
- Capture release notes

---

## 14. Emergency Fix Protocol

### 14.1 When to use

For urgent CVE / runtime fixes that cannot wait for the normal sprint cadence.

### 14.2 Process

1. **Create `hotfix/<scope>` branch** from `main`
2. **Minimum required tests** for impacted scope
3. **Fast-track review** (one approval)
4. **Deploy immediately** after merge
5. **Post-release follow-up PR** to restore normal cadence checks + documentation

### 14.3 Documentation requirement

Even emergency fixes must:
- Cite the official doc (CVE advisory, Spring Security advisory, etc.)
- Document the root cause in the commit message
- Update worklog.md
- Create a follow-up issue for any deferred work

---

## 15. Debt Management Protocol

### 15.1 What is technical debt?

Any code that works but isn't clean:
- `@SuppressWarnings` to silence warnings
- `TODO` / `FIXME` comments
- `catch (Exception)` broad
- Reflection where it's not the documented pattern
- Hardcoded values that should be configurable
- Missing tests for critical paths
- Stale documentation

### 15.2 Debt tracking

- **Worklog entries:** tag with `[DEBT]` in the Task ID
- **Commit messages:** use `chore(debt):` prefix
- **PR labels:** `technical-debt`

### 15.3 Debt repayment rule

> **Rule:** Every sprint must allocate **at least 15% of capacity** to debt repayment.

This prevents debt from accumulating indefinitely.

### 15.4 Debt audit

Before closing each sprint:
- [ ] Search for new `TODO`/`FIXME`/`@SuppressWarnings` introduced
- [ ] Verify no `catch (Exception)` was added without documentation
- [ ] Check that all fixes cite official docs (not just "I know this works")

```bash
# Quick debt audit
grep -rn "TODO\|FIXME\|HACK\|XXX\|@SuppressWarnings" --include="*.java" marketplace-*/src/main/ | wc -l
```

---

## 16. Forbidden Patterns

These patterns must **never** appear in new code. If found in existing code, they should
be fixed at the earliest opportunity.

### 16.1 Code patterns

| Pattern | Why forbidden | Fix |
|---------|---------------|-----|
| `@Autowired` field injection | Untestable, mutable | Constructor injection |
| `catch (Exception e)` broad | Hides bugs, defeats retry | Narrow to specific exception |
| `@SuppressWarnings("unchecked")` | Silences type warnings | Fix the generic type |
| `ReflectionTestUtils.setField` | Brittle, breaks encapsulation | Constructor injection |
| `setAccessible(true)` on private | Breaks encapsulation | Make method package-private |
| `System.out` / `System.err` | No log level, no redaction | SLF4J logger |
| `e.printStackTrace()` | No log level, leaks to stderr | `log.error("msg", e)` |
| `TODO` / `FIXME` / `HACK` | Deferred work | Finish the work now |
| Hardcoded secrets | Security risk | Environment variables |
| `return null` for optional | NPE risk | `Optional<T>` |
| String concatenation in logs | Performance | `{}` placeholders |

### 16.2 Process patterns

| Pattern | Why forbidden | Fix |
|---------|---------------|-----|
| Skipping Stage 1 (Research) | Guessing instead of knowing | Always fetch official docs |
| Skipping Stage 3 (Baseline) | Can't detect regressions | Always record baseline |
| Lowering JaCoCo threshold | Hides coverage gaps | Add tests, don't lower bar |
| Editing Flyway migrations | Breaks production DB | Write new migration |
| Cross-module direct dependency | Breaks Modulith | Use SPI or events |
| `@ConditionalOnBean` cascade | Fragile ordering | `@ConditionalOnProperty` |

---

## 17. Decision Log

### 17.1 Architectural Decisions (ADRs)

Documented in `docs/architecture/ARCHITECTURE.md §8`. Key decisions:

| ADR | Title | Decision |
|-----|-------|----------|
| ADR-001 | Modular Monolith vs Microservices | Modular monolith |
| ADR-002 | Event-Driven Communication | Spring Modulith JDBC log |
| ADR-003 | Cloudflare-First Deployment | CF Container + Neon + Upstash |
| ADR-004 | RotatingJWKSource | 90-day rotation with overlap |
| ADR-005 | Catch DataAccessException only | Let programming errors propagate |

### 17.2 Methodology Decisions

| Decision | Rationale | Date |
|----------|-----------|------|
| 6-stage workflow mandatory | Prevents regressions (proven in PRs #152-#157) | 2026-06-20 |
| Official docs are source of truth | Eliminates guessing | 2026-06-20 |
| 15% debt repayment per sprint | Prevents debt accumulation | 2026-06-20 |
| JaCoCo 70% threshold (not lower) | Maintainable quality bar | 2026-06-20 |
| `verify` is the canonical command | Per Maven official docs | 2026-06-20 |

---

## Appendix A: Quick Reference Card

```
┌─────────────────────────────────────────────────────────────┐
│ BEFORE CODING:                                              │
│ 1. Fetch official doc (Stage 1)                             │
│ 2. Read actual code (Stage 2)                               │
│ 3. Record baseline (Stage 3)                                │
├─────────────────────────────────────────────────────────────┤
│ WHILE CODING (Stage 4):                                     │
│ 4. Follow CODING_STANDARDS.md                               │
│ 5. Cite official doc in Javadoc                             │
│ 6. No forbidden patterns (§16)                              │
├─────────────────────────────────────────────────────────────┤
│ AFTER CODING:                                               │
│ 7. Run same tests (Stage 5) — delta must be 0              │
│ 8. JaCoCo must pass                                        │
│ 9. Modulith verify must pass                                │
│ 10. Update worklog (Stage 6)                                │
│ 11. Commit + push + create PR                               │
├─────────────────────────────────────────────────────────────┤
│ PR BODY MUST INCLUDE:                                       │
│ - Summary                                                   │
│ - Official source(s) cited                                  │
│ - Verification (before/after test counts)                   │
│ - No-regression confirmation                                │
└─────────────────────────────────────────────────────────────┘
```

## Appendix B: Document Hierarchy

```
METHODOLOGY.md (this document)
  ├── CODING_STANDARDS.md       ← How to write code (21 sections)
  ├── architecture/
  │   ├── ARCHITECTURE.md       ← What is the architecture (9 sections + 5 ADRs)
  │   └── diagrams/             ← 5 visual diagrams
  ├── governance/
  │   ├── version-policy.md     ← Version baseline + upgrade cadence
  │   └── risk-register.md      ← Tracked risks + mitigations
  ├── dev/
  │   └── testing-conventions.md ← Test naming + libraries (quick-ref)
  ├── api/
  │   ├── error-codes.md        ← Error taxonomy
  │   └── error-contract.md     ← RFC 7807 + GraphQL envelope
  ├── release/
  │   └── rollout-strategy.md   ← Canary + rollback
  ├── security/
  │   └── secrets-policy.md     ← Secrets + rotation
  ├── deployment/
  │   ├── README.md             ← 5 deployment options
  │   └── aot-cache.md          ← AOT evaluation
  ├── external-services.md      ← Setup guide for 5 external services
  ├── backend-execution-plan.md ← Phases + DoD
  ├── clean-development-plan.md ← Exceptions registry
  └── phase-0-baseline-checklist.md ← Pre-implementation checklist
```

## Appendix C: Official Documentation Index

### Mandatory (must be cited where relevant)

| Topic | URL |
|-------|-----|
| Spring Boot Deployment | https://docs.spring.io/spring-boot/how-to/deployment/index.html |
| Spring Security What's New | https://docs.spring.io/spring-security/reference/whats-new.html |
| Spring Cloud Gateway | https://spring.io/projects/spring-cloud-gateway |
| Maven Guides | https://maven.apache.org/guides/index.html |

### Primary references

| Topic | URL |
|-------|-----|
| Spring Boot Reference | https://docs.spring.io/spring-boot/ |
| Spring Framework Reference | https://docs.spring.io/spring-framework/ |
| Spring Security Reference | https://docs.spring.io/spring-security/reference/ |
| Spring Modulith Reference | https://docs.spring.io/spring-modulith/ |
| Spring Data JPA Reference | https://docs.spring.io/spring-data/jpa/reference/ |
| Spring Guides | https://spring.io/guides |

### Standards

| Topic | URL |
|-------|-----|
| RFC 7807 (Problem Details) | https://datatracker.ietf.org/doc/html/rfc7807 |
| RFC 6238 (TOTP) | https://datatracker.ietf.org/doc/html/rfc6238 |
| RFC 6749 (OAuth 2.0) | https://datatracker.ietf.org/doc/html/rfc6749 |
| RFC 8252 (PKCE) | https://datatracker.ietf.org/doc/html/rfc8252 |
| RFC 9068 (JWT) | https://datatracker.ietf.org/doc/html/rfc9068 |
| RFC 6797 (HSTS) | https://datatracker.ietf.org/doc/html/rfc6797 |
| RFC 7009 (Token Revocation) | https://datatracker.ietf.org/doc/html/rfc7009 |
| NIST SP 800-63B (Authentication) | https://pages.nist.gov/800-63-3/sp800-63b.html |
| NIST SP 800-57 (Key Management) | https://nvd.nist.gov/800-57 |
| OWASP Cheat Sheets | https://cheatsheetseries.owasp.org/ |

### Build & Testing

| Topic | URL |
|-------|-----|
| Maven Build Lifecycle | https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html |
| Maven Multi-Module | https://maven.apache.org/guides/mini/guide-multiple-modules.html |
| Maven Enforcer Plugin | https://maven.apache.org/enforcer/ |
| Surefire | https://maven.apache.org/surefire/ |
| Failsafe | https://maven.apache.org/failsafe/ |
| JaCoCo | https://www.jacoco.org/jacoco/trunk/doc/maven.html |
| JUnit 5 | https://junit.org/junit5/ |
| Mockito | https://site.mockito.org/ |
| AssertJ | https://assertj.github.io/doc/ |
| Instancio | https://www.instancio.org/ |
| Testcontainers | https://www.testcontainers.org/ |

---

**This document is the master protocol. All contributors (human and AI) must follow it.
When in doubt, refer here first, then to the specialized docs.**
