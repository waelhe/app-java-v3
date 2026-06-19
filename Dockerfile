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
# Uses Java itself (already in the image) to check the liveness endpoint — no
# dependency on wget/curl which may or may not be present in minimal Alpine images.
# Checks HTTP 200 status code from the actuator liveness endpoint on the management
# port (8081 in prod, per application-prod.yml management.server.port).
# Reference: https://docs.spring.io/spring-boot/reference/actuator/enabling.html#actuator.enabling.separate-management-port
# Reference: https://docs.docker.com/reference/dockerfile/#healthcheck
HEALTHCHECK --start-period=40s --interval=10s --timeout=3s --retries=3 \
  CMD java -e "var u=new java.net.URL(\"http://127.0.0.1:\"+System.getenv().getOrDefault(\"MANAGEMENT_SERVER_PORT\",\"8081\")+\"/actuator/health/liveness\");var c=(java.net.HttpURLConnection)u.openConnection();c.connect();System.exit(c.getResponseCode()==200?0:1)" || exit 1

ENTRYPOINT ["sh", "-c", "if [ -n \"$PORT\" ] && [ -z \"$SERVER_PORT\" ]; then export SERVER_PORT=$PORT; fi && exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
