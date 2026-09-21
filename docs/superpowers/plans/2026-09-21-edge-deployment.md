# Edge Deployment (Second Railway Service) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish `marketplace-edge` as a second Railway service (`app-java-v3-edge`) in front of `marketplace-app` with TokenRelay + spa CSRF + Redis sessions, using a dedicated Dockerfile and IaC preserve pattern.

**Architecture:** Two independent Railway services (app + edge) sharing the same external stores (Neon PG, Upstash Redis). Edge builds its own layered jar via `jarmode=tools` and proxies `/api/**` to `app-java-v3.railway.internal:8080`. No DB for edge, no code sharing beyond shared infra.

**Tech Stack:** Spring Boot 4.1.1 (jarmode=tools), Spring Cloud Gateway 5.0.3 TokenRelay, Railway IaC (`railway/iac` preserve), Docker multi-stage (eclipse-temurin:25), Maven 3.9.16

**Spec:** `docs/superpowers/specs/2026-09-21-edge-deployment-design.md`

## Global Constraints

- Java 25 (`--release 25`) — `pom.xml:41`
- Spring Boot parent 4.1.1 — `pom.xml:7-10`
- Spring Cloud BOM 2025.1.3 — `pom.xml` edge depManagement exception #14
- No new Maven modules/deps beyond existing Cloud BOM (spec §1 Non-goals)
- Dockerfile recipe verbatim: `java -Djarmode=tools -jar application.jar extract --layers` (Boot 4.1 ref)
- All secrets via `preserve()` — never materialized (`railway.ts:22` precedent)
- Healthcheck: `/actuator/health/liveness`, timeout 300s (same as app)
- Gate: Merge by explicit user word only (§14.3); `mvn verify -pl marketplace-edge` green before push

---

### Task 1: Edge Dockerfile (standalone layered image)

**Files:**
- Create: `marketplace-edge/Dockerfile`
- Test: `marketplace-edge/target/marketplace-edge-*.jar` exists via build

**Interfaces:**
- Consumes: Root `pom.xml` reactor, `marketplace-edge/pom.xml` (already present)
- Produces: Layered image artifact `app.jar` + `app.aot` for Railway build

- [ ] **Step 1: Create Dockerfile with official recipe**

```dockerfile
# marketplace-edge/Dockerfile — mirrors root Dockerfile stages
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN --mount=type=cache,id=s/edge-placeholder-/root/.m2,target=/root/.m2 \
    chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-edge -am

FROM build AS extractor
WORKDIR /app
COPY --from=build /app/marketplace-edge/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-alpine AS trainer
WORKDIR /app
COPY --from=extractor /app/app.jar app.jar
COPY --from=extractor /app/extracted/dependencies/ ./
COPY --from=extractor /app/extracted/spring-boot-loader/ ./
COPY --from=extractor /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor /app/extracted/application/ ./
RUN mkdir -p /var/log/marketplace
RUN java -XX:AOTCacheOutput=/app/app.aot \
    -Dspring.context.exit=onRefresh \
    -Dspring.main.lazy-initialization=true \
    -Dspring.flyway.enabled=false \
    -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
    -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
    -Dspring.jpa.hibernate.ddl-auto=none \
    -Dspring.sql.init.mode=never \
    -Dspring.session.redis.configure-action=none \
    -Dmanagement.opentelemetry.map-environment-variables=false \
    -jar app.jar

FROM eclipse-temurin:25-jre-alpine
RUN apk upgrade --no-cache
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
RUN mkdir -p /var/log/marketplace /app/logs && chown app:app /var/log/marketplace /app/logs
COPY --from=extractor /app/app.jar app.jar
COPY --from=extractor /app/extracted/dependencies/ ./
COPY --from=extractor /app/extracted/spring-boot-loader/ ./
COPY --from=extractor /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor /app/extracted/application/ ./
COPY --from=trainer /app/app.aot app.aot
USER app
EXPOSE 8081
ENV JDK_JAVA_OPTIONS="-XX:AOTCache=/app/app.aot -XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError -Duser.language=en -Duser.country=US"
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Verify build locally (edge module only)**

Run: `.\mvnw.cmd clean package -pl marketplace-edge -am -DskipTests --batch-mode` (PowerShell: `$env:PATH='C:\Program Files\Java\jdk-25.0.4\bin;'+$env:PATH`)
Expected: `BUILD SUCCESS`, jar at `marketplace-edge/target/marketplace-edge-0.1.0-SNAPSHOT.jar`

- [ ] **Step 3: Commit**

```bash
git add marketplace-edge/Dockerfile
git commit -m "feat(edge): standalone Dockerfile for edge BFF (jarmode=tools layered build)"
```

---

### Task 2: Railway IaC — Second Service

**Files:**
- Modify: `.railway/railway.ts:33-94`

**Interfaces:**
- Consumes: `Dockerfile` from Task 1, existing `service("app-java-v3")` pattern
- Produces: `partial = "app-java-v3"` now exports 2 services for `railway config apply`

- [ ] **Step 1: Add edge service definition**

```typescript
  const app_java_v3_edge = service("app-java-v3-edge", {
    source: github("waelhe/app-java-v3", { branch: "main" }),
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "marketplace-edge/Dockerfile",
    },
    healthcheck: "/actuator/health/liveness",
    healthcheckTimeout: 300,
    env: {
      AUTH_SERVER_ISSUER: preserve(),
      EDGE_BACKEND_URL: preserve(),
      EDGE_CLIENT_ID: preserve(),
      EDGE_CLIENT_SECRET: preserve(),
      REDIS_HOST: preserve(),
      REDIS_PORT: preserve(),
      SPRING_DATA_REDIS_PASSWORD: preserve(),
      SPRING_DATA_REDIS_SSL_ENABLED: preserve(),
      SPRING_PROFILES_ACTIVE: preserve(),
    },
  });

  return project("app-java-v3", {
    resources: [app_java_v3, app_java_v3_edge],
  });
```

- [ ] **Step 2: Type-check IaC**

Run: `npx tsc --noEmit --project .railway/tsconfig.json` or `node --check .railway/railway.ts` (if no tsconfig, skip with `railway check` dry-run)
Expected: No type errors

- [ ] **Step 3: Commit**

```bash
git add .railway/railway.ts
git commit -m "feat(edge): Railway second service app-java-v3-edge (preserve envs, liveness healthcheck)"
```

---

### Task 3: Verification & PR

**Files:**
- Test: `marketplace-edge/src/main/resources/application.yml:23` (EDGE_BACKEND_URL default already `http://localhost:8080` — will be overridden to `http://app-java-v3.railway.internal:8080` via env)
- Docs: No code change, but plan file travels

- [ ] **Step 1: Full edge verification**

Run: `.\mvnw.cmd verify -pl marketplace-edge --batch-mode`
Expected: `Tests run: 14, Failures: 0` (10 original + 4 health/https-guard) + `BUILD SUCCESS` + `All coverage checks have been met`

- [ ] **Step 2: Push & PR**

```bash
git push -u origin fix/edge-deploy-second-service
gh pr create --base main --head fix/edge-deploy-second-service --title "feat(edge): deploy edge BFF as second Railway service" --body "Implements spec 2026-09-21-edge-deployment-design.md — tasks 1-2. CI must be green before merge. Merge by user word only."
gh pr comment <num> --body "@coderabbitai review"
```

- [ ] **Step 3: Post-merge ops (owner)**

Set on Railway edge service: `EDGE_BACKEND_URL=http://app-java-v3.railway.internal:8080`, `EDGE_CLIENT_SECRET`, `AUTH_SERVER_ISSUER`, `REDIS_*`, `SPRING_PROFILES_ACTIVE=prod` (all via dashboard `preserve`), add public domain, smoke `GET /actuator/health/liveness` 200.

---

## Self-Review

**Spec coverage:** §3 Dockerfile → Task 1, §5 IaC → Task 2, §7 Testing → Task 3 Step 1, §9 rollout → Task 3 Steps 2-3. All covered.

**Placeholder scan:** No TBD/TODO; all env names concrete, all commands runnable, Dockerfile verbatim from spec.

**Type consistency:** `service` import already in `railway.ts:1`; `github` helper reused; `preserve()` pattern identical to app service.

