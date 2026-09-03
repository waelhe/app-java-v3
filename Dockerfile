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

# Production JVM options: ZGC (generational by default since JDK 24 — the explicit
# ZGenerational flag was removed in 24.0; Railway runtime log of 9dbbbb5:
# "Ignoring option ZGenerational; support was removed in 24.0") + container-aware heap
# UseContainerSupport omitted — enabled by default since Java 10+
# (same principle as @EnableWebSecurity being redundant in Boot)
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

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
