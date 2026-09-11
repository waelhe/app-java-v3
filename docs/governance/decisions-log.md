# Architectural Decision Log (ADL)

> Record every architectural/technology/design decision that would otherwise be re-litigated. Reviewed at session entry for level-4 work (§15.2). When a decision is superseded, move to Superseded — never delete.

---

## Active Decisions

| ID | Date | Context | Decision | Alternatives | Rationale | Status |
|----|------|---------|----------|--------------|-----------|--------|
| D-001 | 2026-05-12 | Governance file ownership | Standalone files; AGENTS/SYSTEM/PROJECT_MAP untouched | Merge into AGENTS.md | Keeps governance portable; no risk to shared docs | accepted |
| D-002 | 2026-05-12 | Canonical language for AI | English `.en.md` is operational canonical; Arabic = mirror | Arabic primary | English = universal codebase language; reduces translation errors in technical refs | accepted |
| D-003 | 2026-05-12 | Section ordering principle | Priority order (truth first), not reading order | Keep original reading order | Priority ordering reduces cognitive load on every session | accepted |
| D-004 | 2026-05-12 | System completeness | The OS must include session-lifecycle, debt-register, decision-log, and security subsystems | Keep 15-section OS | Without a debt loop the system is open-ended — debt accumulates silently | accepted |
| D-005 | 2026-05-12 | Security governance | Security is a merge-blocking gate (§19.1 → §13 DoD), not a standalone advisory doc | Advisory security guide | A gate you must pass beats advice you may skip | accepted |
| D-006 | 2026-09-11 | System integration | OS files = ai-kernel; AGENTS.md (project) = Single System Registry §0.0; old docs are reference layers (§15) — not one mega-file | Merge all into AGENTS.md | Keeps each file's role atomic; cross-refs stay maintainable; zero blind spots | accepted |
| D-007 | 2026-09-11 | Approval exclusivity | Approval mechanism lives ONLY in `opencode.json` + `protocol-enforcer` skill; OS/AGENTS carry zero mechanism text | Keep enforcement in AGENTS.md + OS | Single source of truth for enforcement; skill is on-demand (not always-on), so AGENTS.md auto-load handles activation | accepted |
| D-008 | 2026-09-11 | Activation reliability | `instructions` in project `opencode.json` injects the OS kernel into every session at load; zero-effort activation | Trust AI to load on demand | Eliminates human/forgetfulness failure mode | accepted |
| D-009 | 2026-09-11 | GitHub delivery | Push governance system as independent branch `governance/unified-system`; no merge with origin/main | Force push to main / merge into main | Avoids conflict with in-flight work; allows later PR with full diff review | accepted |
| D-010 | 2026-09-11 | Repo description recovery | Accidentally changed description via API PATCH; recovered from pom.xml: `Two-sided Marketplace Backend - Multi-module Maven + Spring Boot 4 + Spring Modulith` | None — accident recovery | pom.xml `<description>` is the source of truth for project identity | accepted |

---

## Superseded

| ID | Date | Superseded by | Reason |
|----|------|---------------|--------|
| D-001 | 2026-05-12 | D-006 (2026-09-11) | User authorized edits to AGENTS.md and SYSTEM.md for system integration; standalone-only rule no longer holds |

---

## How to use

1. **Record**: after any level-3 or level-4 decision is approved, add a row to Active with all six fields
2. **Reference**: in task briefs (§15.3) cite `ADL D-{N}` as the governing decision
3. **Supersede**: when a new decision replaces an old one, move old to Superseded and note the replacement
4. **Never delete**: history must be traceable — deleted decisions = repeated debates