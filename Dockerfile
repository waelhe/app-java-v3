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

# Copy layers in order: most stable → most volatile
COPY --from=extractor /app/extracted/dependencies/ ./
COPY --from=extractor /app/extracted/spring-boot-loader/ ./
COPY --from=extractor /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor /app/extracted/application/ ./

USER app
EXPOSE 8080

# Production JVM options: Generational ZGC + container-aware heap
# UseContainerSupport omitted — enabled by default since Java 10+
# (same principle as @EnableWebSecurity being redundant in Boot)
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# OCI image best practice — HEALTHCHECK instruction so the image is self-describing.
# Uses the liveness probe endpoint (separate from readiness) per Spring Boot Actuator docs.
# start-period: 40s grace period for JVM warmup before failed health checks count.
# Reference: https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health
# Reference: https://docs.docker.com/reference/dockerfile/#healthcheck
HEALTHCHECK --start-period=40s --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health/liveness" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "if [ -n \"$PORT\" ] && [ -z \"$SERVER_PORT\" ]; then export SERVER_PORT=$PORT; fi && exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
