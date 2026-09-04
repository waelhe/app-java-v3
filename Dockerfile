# ── Build stage ──────────────────────────────────────
# Cache mount: Railway's officially supported Dockerfile cache-mount format
# (docs.railway.com/guides/dockerfiles — "Cache mounts":
#   --mount=type=cache,id=s/<service id>-<target path>,target=<target path>
# copy saved at scripts/prod-design-docs/railway-dockerfiles.txt). The id embeds
# this service's Railway id (4fbac104-…) so Maven's local repository
# (~/.m2 of the build-stage root user, including the mvnw 3.9.16 wrapper
# distribution) survives across builds — the "exploit the cache" guidance of the
# Dockerfile best practices (docs.docker.com — saved copy:
# dockerfile-best-practices.txt). Local Docker/BuildKit treats the id as an
# ordinary cache key, so the same Dockerfile stays portable.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN --mount=type=cache,id=s/4fbac104-c5e5-4dc9-9bb0-08ad2cf02083-/root/.m2,target=/root/.m2 \
    chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-app -am

# ── Extract layers stage ─────────────────────────────
# Per Spring Boot 4.1 Reference: use jarmode=tools (not layertools which is removed).
# Reference: https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html
# "java -Djarmode=tools -jar application.jar extract --layers --destination extracted"
# (copy saved: scripts/prod-design-docs/sb41-dockerfiles.html)
FROM build AS extractor
WORKDIR /app
COPY --from=build /app/marketplace-app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ── AOT training stage ───────────────────────────────
# Official recipe — Spring Boot 4.1 "AOT Cache" (Java 25+), copies saved at
# scripts/prod-design-docs/sb41-aot-cache.html + sb41-howto-aot-cache.html:
#   training run:  java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar app.jar
#   production:    java -XX:AOTCache=app.aot -jar app.jar
# The how-to's "Preventing Remote Services Interaction During the Training Run"
# section sanctions configuration customizations for the training run; ours come
# from the Spring team's own per-dependency recipes (spring-lifecycle-smoke-tests,
# saved: lifecycle-jpa.adoc / lifecycle-flyway.adoc / lifecycle-redis.adoc):
#   - flyway off, Hibernate dialect pinned + JDBC metadata access disabled,
#     sql init never (JPA recipe)  - session Redis configure-action=none (Spring
#     Session, repository-type: indexed)  - lazy singletons: SAS 7.1.1
#     JdbcOAuth2AuthorizationService's constructor probes column metadata over a
#     live connection (initColumnMetadata → DatabaseMetaData.getColumns — source
#     saved at scripts/verify-aud-claim/sas-all/), and Railway builds expose no
#     database service, so lazy-initialization (documented Boot property) is the
#     recipe-compliant way to complete refresh without remote systems.
# exit=onRefresh halts BEFORE SmartLifecycle start (source-verified:
# DefaultLifecycleProcessor.onRefresh — spring-context 7.0.9 sources) and the
# training exits 0 (verified locally: 36.7s, cache ~177 MB, boot path −38%).
# Same base image as the runtime stage ⇒ identical JVM version ("the same Java
# version" requirement of the recipe) — and training runs in /app, the exact
# directory the runtime uses, so the recorded classpaths match.
FROM eclipse-temurin:25-jre-alpine AS trainer
WORKDIR /app
COPY --from=extractor /app/extracted/dependencies/ ./
COPY --from=extractor /app/extracted/spring-boot-loader/ ./
COPY --from=extractor /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor /app/extracted/application/ ./
# Writable log dir for the training boot (logback FILE appender) — keeps build
# logs free of alarming-but-harmless appender errors.
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
    -jar app.jar

# ── Runtime stage ────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# Writable log directories for the non-root user (both profiles):
# - /var/log/marketplace — logback FILE appender, default of
#   logging.file.name: ${LOG_FILE:/var/log/marketplace/application.log} (logback
#   errors in the cf0a1a9 Railway runtime log: "openFile(/var/log/marketplace/
#   application.log,true) call failed. java.io.FileNotFoundException")
# - /app/logs — Tomcat access log (server.tomcat.accesslog.directory: logs,
#   relative to the /app workdir); without it the valve cannot create its files
#   on first request.
RUN mkdir -p /var/log/marketplace /app/logs \
    && chown app:app /var/log/marketplace /app/logs

# Copy layers in order: most stable → most volatile (unchanged official recipe;
# keeping the four separate COPYs preserves Docker layer caching of the
# dependency layers across code-only changes).
COPY --from=extractor /app/extracted/dependencies/ ./
COPY --from=extractor /app/extracted/spring-boot-loader/ ./
COPY --from=extractor /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor /app/extracted/application/ ./
# AOT cache produced by the training stage above — a separate layer so its
# invalidation (application/deps change ⇒ cache stale) does not disturb the
# dependency layers.
COPY --from=trainer /app/app.aot app.aot

USER app
EXPOSE 8080

# Production JVM options — sized for a 1 GB container (Railway limits for this
# service, serviceInstanceLimits: memoryBytes=1000000000 ≈ 953 MiB, cpu=2):
#   The 57cdc9e0 deploy (7328ee0 — DB fix verified working: Hikari connected,
#   "Database: jdbc:postgresql://postgres.railway.internal:5432", Flyway
#   "Successfully validated 28 migrations") was OOM-killed silently mid-Hibernate:
#   Railway metrics for that deploy show MEMORY_USAGE_GB 0.999/0.941/0.998/0.998
#   vs MEMORY_LIMIT_GB 1.000 — the process hit the cgroup ceiling; a cgroup OOM
#   kill produces NO Java exception (matches the logs cutting off mid-startup,
#   first cycle crawling ~2 min under GC pressure before the kill).
#   Old flags -XX:+UseZGC -XX:MaxRAMPercentage=75 → 715 MB max heap, leaving
#   only ~240 MB for metaspace (Hibernate/Envers/Modulith/GraphQL/18 repositories
#   ≈ 200+ MB) + code cache + thread stacks + ZGC metadata + netty direct
#   buffers — over budget.
#   Fix: drop ZGC for G1 (the JDK default collector — least exotic, best-tested
#   path; OpenJDK positions ZGC for sub-ms latency on "8MB to 16TB" heaps, but
#   its colored-pointer metadata and load barriers buy nothing at ~1 GB where
#   boot-time footprint is the binding constraint) and drop the heap share to
#   60% (~572 MB) — leaving ~380 MB for the non-heap side.
# UseContainerSupport omitted — enabled by default since Java 10+
# (same principle as @EnableWebSecurity being redundant in Boot).
#
# Delivered through JDK_JAVA_OPTIONS (the official `java` launcher environment
# variable — "Using the JDK_JAVA_OPTIONS Launcher Environment Variable", JDK 25
# java man page, saved: jdk25-java-manual.html) so the ENTRYPOINT stays the pure
# exec-form recipe. Scoped to this stage only: the build/extractor/trainer
# stages never see it. Includes the AOT cache flag from the training stage.
# Measured locally on this app's boot path: −38% time-to-context with the cache.
ENV JDK_JAVA_OPTIONS="-XX:AOTCache=/app/app.aot -XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError"

# The pure official Spring Boot 4.1 recipe (same reference page as above):
#   ENTRYPOINT ["java", "-jar", "application.jar"]
#   "This jar only contains application code and references to the extracted jar files"
# The old org.springframework.boot.loader.launch.JarLauncher no longer exists in the
# 4.1 layered layout (spring-boot-loader layer is empty; thin-jar MANIFEST carries
# Main-Class: com.marketplace.MarketplaceApplication + Class-Path: lib/…) — the 9dbbbb5
# deploy crashed with "Could not find or load JarLauncher". Verified locally:
# merged layers + `java -jar app.jar` boots MarketplaceApplication through Tomcat.
# Two responsibilities of the former hybrid shell entrypoint moved to the
# application layer (its official home):
#   - JWT keystore materialization: SecurityConfig.jwkSource now decodes
#     JWT_KEYSTORE_B64 in memory (KeyStore.load over a ByteArrayInputStream) —
#     no file, no shell; rotation stays "update the variable" (keys/README.md §4).
#   - PORT→SERVER_PORT mapping: application.yml now binds
#     server.port: ${PORT:8080} — "Railway will inject a PORT environment
#     variable that your application should listen on" (docs.railway.com —
#     saved: railway-healthchecks.txt) via the documented placeholder mechanism.
# exec form also restores direct PID 1 for the JVM (signal hygiene — Dockerfile
# best practices: "the exec form ... makes the command run as PID 1").
ENTRYPOINT ["java", "-jar", "app.jar"]
