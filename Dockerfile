# ── Build stage ──────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-app -am

# ── Extract layers stage ─────────────────────────────
FROM build AS extractor
WORKDIR /app
COPY --from=build /app/marketplace-app/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ── Runtime stage ─────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# Copy layers in order: most stable → most volatile
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./

USER app
EXPOSE 8080

# Production JVM options: Generational ZGC + container-aware heap
# UseContainerSupport omitted — enabled by default since Java 10+
# (same principle as @EnableWebSecurity being redundant in Boot)
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# OCI image best practice — HEALTHCHECK instruction so the image is self-describing.
# Uses wget (included in Alpine's BusyBox) to check the liveness endpoint on the
# management port (8081 in prod, per application-prod.yml management.server.port).
# Reference: https://docs.spring.io/spring-boot/reference/actuator/enabling.html#actuator.enabling.separate-management-port
# Reference: https://docs.docker.com/reference/dockerfile/#healthcheck
# Note: eclipse-temurin:*-alpine is based on Alpine Linux which includes BusyBox wget.
# Reference: https://hub.docker.com/_/eclipse-temurin
HEALTHCHECK --start-period=40s --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-8081}/actuator/health/liveness" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "if [ -n \"$PORT\" ] && [ -z \"$SERVER_PORT\" ]; then export SERVER_PORT=$PORT; fi && exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
