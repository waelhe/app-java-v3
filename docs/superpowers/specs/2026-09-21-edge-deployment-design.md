# Edge Deployment — Second Railway Service (BFF Gateway) — Design

**Date:** 2026-09-21
**Branch base:** `origin/main@a41bd8f` (edge 22-module reactor, post-#360 durability decision: free-tier, AOF/RDB on compose, Upstash managed, eviction DISABLED)
**Governing docs:** `docs/security/client-hosting-strategy-plan.md` §4 pattern 1 + §6 + `docs/superpowers/plans/2026-09-20-edge-bff-gateway.md` Tasks 1-5 (done, on main) + `SYSTEM.md` §1/§4/§10 + `AGENTS.md` governance
**Official references (verbatim, fetched):** Spring Cloud Gateway 5.0.3 TokenRelay (`/spring-cloud-gateway-server-webmvc/filters/tokenrelay.html` — `TokenRelay=` with `oauth2Login()` forwards `currently authenticated user's own access token`; requires `spring.security.oauth2.client.*` → `OAuth2AuthorizedClientManager`); Spring Boot 4.1 packaging (`jarmode=tools extract --layers`); Railway IaC (`defineRailway/service/preserve`)

---

## 1. Goal & Non-Goals

**Goal:** Publish `marketplace-edge` as a **separate Railway service** in front of `marketplace-app`, with no change to the app's image or DB topology. Traffic: `Internet → edge:8081 (TokenRelay + spa CSRF + Redis sessions) → app:8080 (internal)` via Railway private network.

**Non-goals:** No DB for edge (remains stateless: sessions external in Upstash, claims in JWT), no JWT key duplication (edge is OAuth2 client, not issuer), no new Maven modules/deps beyond Cloud BOM already on edge.

**Success criteria:** (1) `mvn verify -pl marketplace-edge` green on main + new Dockerfile.edge, (2) Railway `railway config apply` dry-run clean, (3) live smoke: `GET /actuator/health/liveness` 200 on edge public domain + `GET /api/v1/search/**` through edge with TokenRelay (401→302→200 after login) on staging.

---

## 2. Architecture

```
[Browser/Next.js] --HTTPS--> [edge (Railway service app-java-v3-edge):8081]
                                | oauth2Login + TokenRelay= + csrf.spa() + Redis sessions (Upstash)
                                | EDGE_BACKEND_URL=http://app-java-v3.railway.internal:8080
                                v (private network, Railway DNS)
                            [app (Railway service app-java-v3):8080]
                                | SAS 7.1.1 + Modulith 2.1.1 + Neon PG + Upstash Redis (same external stores)
```

**Why 2 services, not 1 fat jar:** Policy §14.3 (explicit user gate for hosting), plus Dockerfile best practice (one concern per image) and independent scaling/health. The stateless edge scales horizontally without DB migrations; the app scales on PG/Redis load.

**Why Railway private DNS:** `*.railway.internal` is the official intra-project mesh (no egress, no TLS hop); edge's `EDGE_BACKEND_URL` defaults to it in prod, `http://localhost:8080` locally.

---

## 3. Components & Files Touched

| Component | File | Change (surgical) |
|---|---|---|
| Edge image | `marketplace-edge/Dockerfile` (new) | Multi-stage: `mvnw -pl marketplace-edge -am package` → `jarmode=tools extract` → JRE runtime with same AOT/cache pattern as root Dockerfile (proven recipe) |
| IaC | `.railway/railway.ts` | Add `service("app-java-v3-edge", { source: github(... main), build: { dockerfilePath: "marketplace-edge/Dockerfile" }, healthcheck: "/actuator/health/liveness", env: { EDGE_*, REDIS_*, AUTH_SERVER_ISSUER, SPRING_PROFILES_ACTIVE } })` + add to `project.resources` |
| Docs | `docs/superpowers/plans/2026-09-20-edge-bff-gateway.md` + `SYSTEM.md` §1 | Record deployed topology (22→22, second service) |

No change to `marketplace-app` Dockerfile, `pom.xml` reactor, or DB migrations.

---

## 4. Build — Official Recipe (Spring Boot 4.1)

Per `docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html` (verbatim: `java -Djarmode=tools -jar application.jar extract --layers --destination extracted`), edge Dockerfile mirrors root Dockerfile:

1. `FROM eclipse-temurin:25-jdk-alpine AS build` + `COPY . .` + `RUN --mount=type=cache,id=s/<edge-service-id>-/root/.m2 mvnw -pl marketplace-edge -am package -DskipTests`
2. Extractor + AOT trainer (same 5 flags as root: lazy, flyway off, JPA dialect pinned, session configure-action none, OTel env mapping off)
3. Runtime `eclipse-temurin:25-jre-alpine` + `apk upgrade --no-cache` + `COPY --from=extractor` layers + `COPY --from=trainer app.aot` + `JDK_JAVA_OPTIONS` (AOT + MaxRAM%)

Cache id uses edge service id (to be filled after first `railway service create`, like `30294a45` for app).

---

## 5. Environment & IaC — Preserve Pattern

All secrets via `preserve()` (never materialized in repo — `.railway/railway.ts:22` precedent):

- `EDGE_CLIENT_ID` / `EDGE_CLIENT_SECRET` (D6 prod guard already in EdgeSecurityConfig)
- `EDGE_BACKEND_URL` = `http://app-java-v3.railway.internal:8080` (prod) / `http://localhost:8080` (local)
- `AUTH_SERVER_ISSUER` (already present, reused)
- `REDIS_HOST/PORT/PASSWORD/SPRING_DATA_REDIS_SSL_ENABLED` (Upstash managed — same as app)
- `SPRING_PROFILES_ACTIVE=prod`

Healthcheck `300s` (same as app) on `/actuator/health/liveness` (edge exposes it via `management.endpoints.web.exposure.include: health`).

---

## 6. Data Flow — TokenRelay (Official)

Per Gateway 5.0.3 doc (fetched): `TokenRelay` with no `clientRegistrationId` forwards `currently authenticated user's own access token (obtained during oauth2Login)` in `Authorization` header. EdgeSecurityConfig `oauth2Login()` + `spring.security.oauth2.client.*` already trigger `OAuth2AuthorizedClientManager`. No code change in edge for this publish.

---

## 7. Testing

- **Unit:** `mvn verify -pl marketplace-edge` (14 tests: relay, sessions, CSRF, D6, health permitAll, https guard) — must stay green.
- **IaC:** `npx railway check` (or `railway config apply --dry-run` if available) — validates `railway.ts` shape.
- **Live smoke (after deploy):** `curl` edge public URL `/actuator/health/liveness` 200 → login → `curl -H "Authorization: Bearer <edge-token>" /api/v1/search` 200 via edge; direct app bypass blocked by Railway private-network ACL.

---

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Double build in CI (both services) | Root `mvn verify` already builds full reactor (22 modules) — no extra CI; Docker builds are per-service on Railway, cached by `s/<id>-/root/.m2` |
| Secret sprawl | `preserve()` only; no new secret files |
| Session loss on Upstash (free tier, no backups) | Documented accepted risk (silent SSO re-auth); eviction DISABLED — no silent eviction |

---

## 9. Rollout

1. PR with `marketplace-edge/Dockerfile` + `railway.ts` second service → CI green → squash merge.
2. `railway service create` (one-time, UI) → set envs on new service (preserve) → `railway up` auto-deploys from main.
3. Add public domain to edge service (Railway Domains) → smoke tests → docs truth-sync.

---

## 10. Self-Review

- Placeholders: none (all env names concrete, all refs fetched verbatim).
- Consistency: 2-service topology matches §2 diagram and `railway.ts` resources array; no DB for edge (stateless) holds.
- Scope: Single PR, 2 files + docs — no Modulith boundary touch (verified: edge outside Modulith, zero `allowedDependencies`).
- Ambiguity: `EDGE_BACKEND_URL` default explicit (`railway.internal` prod, localhost dev); health path explicit (`/actuator/health/liveness`).
