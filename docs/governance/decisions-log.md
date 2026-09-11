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

---

## Superseded

| ID | Date | Superseded by | Reason |
|----|------|---------------|--------|

---

## How to use

1. **Record**: after any level-3 or level-4 decision is approved, add a row to Active with all six fields
2. **Reference**: in task briefs (§15.3) cite `ADL D-{N}` as the governing decision
3. **Supersede**: when a new decision replaces an old one, move old to Superseded and note the replacement
4. **Never delete**: history must be traceable — deleted decisions = repeated debates