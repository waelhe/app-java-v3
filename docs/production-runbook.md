# Production Runbook (Marketplace Platform)

Last updated: 2026-05-19 (UTC)

## 1) Runtime Profile and Startup

- Active profile for production must be explicitly set:
  - `SPRING_PROFILES_ACTIVE=prod`
- Start command (packaged app):

```bash
java -jar marketplace-app/target/marketplace-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

## 2) Required Environment Variables

> Keep secrets in a vault/secret manager; do not commit them.

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`
- `SPRING_FLYWAY_ENABLED=true`
- `SPRING_REDIS_HOST`
- `SPRING_REDIS_PORT`
- `LOGGING_LEVEL_ROOT=INFO`

## 3) Health and Readiness Checks

- Liveness/health:
  - `GET /actuator/health`
- Readiness (when enabled by config group):
  - `GET /actuator/health/readiness`
- Metrics:
  - `GET /actuator/metrics`

Smoke check commands:

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/actuator/metrics
```

## 4) Logging and Traceability

- Default level: `INFO` in prod.
- Avoid logging PII and secrets.
- Preserve correlation identifiers across API boundaries.
- Prefer async appenders in high-throughput environments.

## 5) Release Verification Checklist

1. Build and verify artifacts:
   ```bash
   mvn -q -DskipTests verify
   ```
2. Run tests in CI gate:
   ```bash
   mvn test
   ```
3. Confirm DB migrations are present and ordered.
4. Confirm actuator endpoints exposed only as needed by ops/security policy.

## 6) Rollback Guidance

- Keep previous container image / JAR available.
- Roll back app version first if migration is backward-compatible.
- If migration is not backward-compatible, follow DB rollback plan before traffic switch.

## Official References

- Spring Boot Actuator: https://docs.spring.io/spring-boot/reference/actuator/index.html
- Spring Boot Externalized Configuration: https://docs.spring.io/spring-boot/reference/features/external-config.html
- Spring Boot Profiles: https://docs.spring.io/spring-boot/reference/features/profiles.html
- Spring Guides: https://spring.io/guides
