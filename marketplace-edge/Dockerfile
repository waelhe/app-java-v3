# ── Build stage ──────────────────────────────────────
# Mirrors root Dockerfile recipe (jarmode=tools) for the isolated edge service.
# Cache id carries the REAL v4 edge service id (ee9a69ef — service
# app-java-v3-edge on project successful-simplicity, created 2026-10-09):
# Railway's documented contract (docs.railway.com/builds/dockerfiles — Cache
# mounts) requires the id to match the PUBLISHING service, otherwise the build
# is rejected before scheduling (the measured v3→v4 migration trap,
# runbook §10.2). Portable locally regardless.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN --mount=type=cache,id=s/ee9a69ef-16ee-43b6-9e1b-42e19de56b04-/root/.m2,target=/root/.m2 \
    chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-edge -am

# ── Extract layers stage ─────────────────────────────
# Per Spring Boot 4.1 Reference: jarmode=tools (layertools removed)
# "java -Djarmode=tools -jar application.jar extract --layers --destination extracted"
FROM build AS extractor
WORKDIR /app
COPY --from=build /app/marketplace-edge/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ── AOT training stage ───────────────────────────────
# Same 5 flags as root Dockerfile trainer (lazy, flyway off, JPA pinned,
# session configure-action none, OTel env mapping off) — verified recipe.
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

# ── Runtime stage ────────────────────────────────────
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
