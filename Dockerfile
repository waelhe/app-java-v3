# ── Build stage ──────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-app -am

# ── Extract layers stage ─────────────────────────────
# Per Spring Boot 4.1 Reference: use jarmode=tools (not layertools which is removed).
# Reference: https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html
# "java -Djarmode=tools -jar application.jar extract --layers --destination extracted"
FROM build AS extractor
WORKDIR /app
COPY --from=build /app/marketplace-app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ── Runtime stage ─────────────────────────────────────
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
RUN mkdir -p /var/log/marketplace /app/logs && chown app:app /var/log/marketplace /app/logs

# Copy layers in order: most stable → most volatile
COPY --from=extractor /app/extracted/dependencies/ ./
COPY --from=extractor /app/extracted/spring-boot-loader/ ./
COPY --from=extractor /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor /app/extracted/application/ ./

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
# (same principle as @EnableWebSecurity being redundant in Boot)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError"

# Launch the THIN jar extracted by jarmode=tools — the Spring Boot 4.1 official
# recipe (same reference page as above, saved: scripts/prod-design-docs/sb41-dockerfiles.html):
#   ENTRYPOINT ["java", "-jar", "application.jar"]
#   "This jar only contains application code and references to the extracted jar files"
# The old org.springframework.boot.loader.launch.JarLauncher no longer exists in the
# 4.1 layered layout (spring-boot-loader layer is empty; thin-jar MANIFEST carries
# Main-Class: com.marketplace.MarketplaceApplication + Class-Path: lib/…) — the 9dbbbb5
# deploy crashed with "Could not find or load main class …JarLauncher". Verified locally:
# merged layers + `java -jar app.jar` boots MarketplaceApplication through Tomcat
# (DB connection is the only failure when no database is present, as expected).
ENTRYPOINT ["sh", "-c", "if [ -n \"$PORT\" ] && [ -z \"$SERVER_PORT\" ]; then export SERVER_PORT=$PORT; fi && exec java $JAVA_OPTS -jar app.jar"]
