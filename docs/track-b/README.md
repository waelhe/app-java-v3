# docs/track-b-state — Track B session-state mirror

> **Purpose (the owner's word, 2026-10-07):** «واصلُ بلا انتظار سلبي، واحفظ كل شيء على جيت هاب قد يفقد بانتهاء الجلسة» —
> this branch is the durable GitHub home for everything belonging to the Track B developer's session that would otherwise
> die with the sandbox: the institutional worklog, the owner's verbatim start instructions, and the measured build evidence.

## Contents

| Path | What it is |
|---|---|
| `worklog.md` | Mirror of the live multi-agent worklog (the two shifts' institutional memory, per parallel-plan §8.5). The live original lives in the developer's sandbox; this copy is re-mirrored **after every unit push**. |
| `handoff-message.md` | The owner's verbatim Track-B developer start message (bootstrap, ownership gardens, the ten binding rules, the three stopping events, red lines, worklog template). |
| `evidence/b01-reactor-2.log` | The B-01 clean full-reactor proof: **BUILD SUCCESS 22/22 modules, 5,598 tests, 6:39** (JDK 25, user-space Redis 7.2.5 + PostgreSQL 16.4). |
| `evidence/b01-reactor.log` | The first honest reactor attempt (killed mid-run by the sandbox — the documented environment limitation). |
| `evidence/b01-edge.log` | The edge module chunked-run evidence from the B-01 bootstrap round. |

## Policy

- This branch is a **state mirror only** — it is NOT part of the merge ladder (parallel plan §15) and is **never merged to `main`**.
- Secrets were scanned before the first push: no tokens, no credentials (the reactor logs carry Maven's own `[REDACTED:github_token]` redaction; the handoff message's clone line uses a command-substitution placeholder, not a literal).
- Update cadence: one commit per unit (B-xx) re-mirroring the worklog, riding alongside the unit's push to `feat/track-b-modules`.
