# Marketplace Backend — Deployment Guide

This document covers 5 deployment options for the Marketplace backend application.

---

## Prerequisites

- **Java 25** (JDK for build, JRE for runtime)
- **PostgreSQL 17** (database)
- **Redis 7** (cache + session store)
- **Maven 3.9.16** (build tool, or use `./mvnw` wrapper)

### Environment Variables

All deployment methods require these environment variables (see `.env.example` for a template):

| Variable | Description | Example |
|----------|-------------|---------|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/marketplace` |
| `DB_USERNAME` | Database username | `marketplace` |
| `DB_PASSWORD` | Database password | `change_me` |
| `REDIS_HOST` | Redis hostname | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `CORS_ALLOWED_ORIGINS` | Comma-separated allowed origins | `https://marketplace.com` |
| `AUTH_SERVER_ISSUER` | JWT issuer URL | `https://api.marketplace.com` |
| `JWT_KEYSTORE_PATH` | Path to JKS keystore | `/opt/marketplace/keystore.jks` |
| `JWT_KEYSTORE_PASSWORD` | Keystore password | `change_me` |
| `JWT_KEY_ALIAS` | Key alias in keystore | `marketplace-auth` |
| `JWT_KEY_PASSWORD` | Key password | `change_me` |

---

## Option 1: Development (`mvn spring-boot:run`)

Best for local development.

```bash
# Start PostgreSQL + Redis
docker compose up postgres redis -d

# Run the application
./mvnw spring-boot:run -pl marketplace-app -Dspring-boot.run.profiles=dev
```

The app starts on `http://localhost:8080` with dev profile (H2 console, debug logs).

Reference: https://docs.spring.io/spring-boot/how-to/deployment/index.html

---

## Option 2: Docker Compose (Full Stack)

Best for staging / testing / single-server deployment.

```bash
# Build and start all services (PostgreSQL + Redis + App)
docker compose up --build -d

# View logs
docker compose logs -f app

# Stop
docker compose down
```

The app starts on `http://localhost:8080`, actuator on `http://localhost:8081`.

The `docker-compose.yml` includes:
- `postgres` — PostgreSQL 17 Alpine
- `redis` — Redis 7 Alpine
- `app` — Marketplace application (built from `Dockerfile`)

Reference: https://docs.docker.com/compose/

---

## Option 3: OCI Image (`mvn spring-boot:build-image`)

Best for Kubernetes / container orchestration.

```bash
# Build the OCI image (requires Docker daemon)
./mvnw spring-boot:build-image -pl marketplace-app

# Run the image
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/marketplace \
  -e DB_USERNAME=marketplace \
  -e DB_PASSWORD=marketplace \
  -e REDIS_HOST=host.docker.internal \
  marketplace:0.1.0-SNAPSHOT
```

The image is built using Spring Boot's Cloud Native Buildpacks integration
(no Dockerfile needed for this method).

Reference: https://docs.spring.io/spring-boot/maven-plugin/build-image.html

---

## Option 4: systemd Service (Dedicated Server)

Best for bare-metal / VPS deployment without Docker.

```bash
# Build the JAR
./mvnw clean package -DskipTests -pl marketplace-app -am

# Copy to server
scp marketplace-app/target/*.jar user@server:/opt/marketplace/app.jar

# Install systemd service
sudo cp docs/deployment/marketplace.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable marketplace
sudo systemctl start marketplace

# Check status
sudo systemctl status marketplace
```

Reference: https://docs.spring.io/spring-boot/how-to/deployment/installing.html

---

## Option 5: Direct JAR (`java -jar`)

Simplest option — just run the JAR.

```bash
# Build
./mvnw clean package -DskipTests -pl marketplace-app -am

# Run
java -jar marketplace-app/target/*.jar \
  --spring.profiles.active=prod \
  --DB_URL=jdbc:postgresql://localhost:5432/marketplace \
  --DB_USERNAME=marketplace \
  --DB_PASSWORD=marketplace \
  --REDIS_HOST=localhost
```

Reference: https://docs.spring.io/spring-boot/how-to/deployment/index.html

---

## Health Checks

All deployment methods expose actuator endpoints:

| Endpoint | Port | Purpose |
|----------|------|---------|
| `/actuator/health/liveness` | 8081 | Liveness probe (is the app running?) |
| `/actuator/health/readiness` | 8081 | Readiness probe (is the app ready to serve?) |
| `/actuator/info` | 8081 | Build info + git commit |
| `/actuator/prometheus` | 8081 | Prometheus metrics |

Reference: https://docs.spring.io/spring-boot/reference/actuator/endpoints.html

---

## Optional: AOT Cache (Startup Optimization)

For high-volume production deployments where cold-start latency matters
(Kubernetes pod scale-up, FaaS), see **[aot-cache.md](aot-cache.md)** for the
officially-recommended AOT Cache workflow (Java 25+ JVM feature).

> ⚠️ Do NOT confuse AOT Cache (safe, recommended) with AOT Processing / GraalVM
> Native Image (incompatible with this project's `@Profile` and
> `@ConditionalOnProperty` usage). See the doc for details.

Reference: https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html

---

## JWT Keystore Generation

Generate a keystore for JWT signing (required for production):

```bash
keytool -genkeypair \
  -alias marketplace-auth \
  -keyalg RSA \
  -keysize 2048 \
  -keystore /opt/marketplace/keystore.jks \
  -validity 365 \
  -storepass change_me \
  -keypass change_me \
  -dname "CN=marketplace, OU=Engineering, O=Marketplace, L=Riyadh, ST=Riyadh, C=SA"
```

Reference: https://docs.spring.io/spring-security/reference/servlet/oauth2/server-authorization/jwk.html
