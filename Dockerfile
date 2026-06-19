# ── Build stage ──────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-app -am

# ── Extract layers stage ─────────────────────────────
# Per Spring Boot Reference: use jarmode=tools (not layertools which is deprecated).
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

# Copy layers in order: most stable -> most volatile
# Per Spring Boot Reference: COPY from extracted/ directory.
COPY --from=extractor /app/extracted/dependencies/ ./
COPY --from=extractor /app/extracted/spring-boot-loader/ ./
COPY --from=extractor /app/extracted/snapshot-dependencies/ ./
COPY --from=extractor /app/extracted/application/ ./

USER app
EXPOSE 8080

# Production JVM options: Generational ZGC + container-aware heap
# UseContainerSupport omitted -- enabled by default since Java 10+
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# OCI image best practice -- HEALTHCHECK instruction so the image is self-describing.
# Uses wget (included in Alpine's BusyBox) to check the liveness endpoint on the
# management port (8081 in prod, per application-prod.yml management.server.port).
# Reference: https://docs.spring.io/spring-boot/reference/actuator/enabling.html
# Reference: https://docs.docker.com/reference/dockerfile/#healthcheck
HEALTHCHECK --start-period=40s --interval=10s --timeout=3s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-8081}/actuator/health/liveness" | grep -q '"status":"UP"' || exit 1

# Per Spring Boot Reference: ENTRYPOINT uses "java -jar application.jar"
# (not JarLauncher which is the legacy uber-jar launcher).
# Reference: https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html
# "ENTRYPOINT ["java", "-jar", "application.jar"]"
ENTRYPOINT ["sh", "-c", "if [ -n \"$PORT\" ] && [ -z \"$SERVER_PORT\" ]; then export SERVER_PORT=$PORT; fi && exec java $JAVA_OPTS -jar app.jar"]
