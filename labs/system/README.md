# labs/system — Deep System Map (isolated, AI must read before touching)

> **Purpose:** so any AI understands EXACTLY what system it touches before any operation — no blindness.
> **Sources:** the code itself, verified by reading on the current working tree. Every fact cites `file:line`.
> **Format:** `TYPE | FACT | [evidence] | CITATION`. `[code]`=verified in code · `[doc]`=project doc · `[test]`=test evidence.
> **Load order:** README (this) → 00-overview → the family you touch (01-modules/02-security/03-data/04-api/05-config/06-testing).
> **Encoding:** UTF-8. **Isolation:** `labs/` only; never touches `docs/governance/`.

## Files

| File | Content |
|---|---|
| `00-overview.md` | System at a glance: stack, 19 modules, composition root, key invariants. |
| `01-modules.md` | Module topology, boundaries, ports/adapters, events (who publishes/consumes). |
| `02-security.md` | 3 chains, SAS/JWT, method security, CORS, secrets, gates. |
| `03-data.md` | Flyway, BaseEntity, entities, repositories, transactions, Redis. |
| `04-api.md` | Full endpoint table + versioning + error contract + OpenAPI. |
| `05-config.md` | application.yml, profiles, env vars (fail-fast), events, scheduling, observability. |
| `06-testing.md` | Test types, Testcontainers, Modulith/architecture tests, CI gates, commands. |

## The golden rules (read before ANY touch)

1. **Backend anchored (§0.3):** backend/services/data are unified and closed; a client = config + test, zero new backend code.
2. **Boundaries:** import only `@NamedInterface` packages; adapters are internal; cross-module via ports/events.
3. **`@Transactional` on services, called externally** (proxy); `readOnly=true` on reads.
4. **Flyway forward-only:** never edit an applied migration; new `V{n+1}`.
5. **Secrets in env only** — never in code/Git; prod fail-fast on missing.
6. **Verify with `mvn clean verify`** (or `-pl <module> -am`) before claiming done.