# AOT Cache — Optional Startup-Time Optimization

This document evaluates the two Spring Boot "Ahead-of-Time" options against the
Marketplace backend and recommends a safe path. **Read this before enabling
either option** — they are not interchangeable.

> **TL;DR**
> - ✅ **AOT Cache** (Java 25+ JVM feature) is **safe** and **recommended** as an
>   optional optimization. It is a runtime cache, not a code transformation.
> - ❌ **AOT Processing / GraalVM Native Image** is **NOT compatible** with this
>   project as-is. It requires removing `@Profile`, `@ConditionalOnProperty`,
>   and `@ConditionalOnBean` usage first.

---

## 1. The two options are different

| Aspect | AOT Cache | AOT Processing (Native Image) |
|--------|-----------|-------------------------------|
| **What it is** | JVM feature (Java 25+) that caches class loading & initialization | Spring's closed-world build-time code generation |
| **Build flag** | None (runtime only) | `mvn -Pnative package` |
| **Runtime flag** | `-XX:AOTCache=app.aot` | `-Dspring.aot.enabled=true` or native binary |
| **Closed-world?** | No — beans can still be conditional at runtime | Yes — bean set is fixed at build time |
| **`@Profile` works?** | ✅ Yes | ⚠️ "has limitations" |
| **`@ConditionalOnProperty` works?** | ✅ Yes | ❌ "not supported" |
| **`@ConditionalOnBean` works?** | ✅ Yes | ⚠️ Fragile (bean ordering) |
| **Startup speedup** | ~30-50% | ~10-100x (sub-100ms possible) |
| **Memory reduction** | Modest | Large (~50MB vs ~300MB) |
| **Risk to this project** | None | High — breaks DevDataInitializer + EmailService |

---

## 2. What the official documentation says

### 2.1 AOT Cache (the safe option)

> **Official doc** — <https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html>:
>
> "AOT cache is a JVM feature that can help reduce the startup time and memory
> footprint of Java applications."
>
> "Spring Boot supports the AOT cache for Java 25 and above. If you're using an
> earlier version of Java, you have to use CDS instead."
>
> *"Spring Boot supports both CDS and AOT cache, however, we recommend using the
> AOT cache whenever possible."*
>
> Workflow:
> ```
> $ java -Djarmode=tools -jar my-app.jar extract --destination application
> $ cd application
> $ java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar my-app.jar
> $ java -XX:AOTCache=app.aot -jar my-app.jar
> ```
>
> "You have to use the cache file with the extracted form of the application,
> otherwise it has no effect."

### 2.2 AOT Processing (the incompatible option)

> **Official doc** — <https://docs.spring.io/spring-boot/reference/packaging/aot.html>:
>
> "**Beware that using the ahead-of-time processing has drawbacks.** It implies
> the following restrictions:
> - The classpath is fixed and fully defined at build time
> - The beans defined in your application cannot change at runtime, meaning:
>   - The Spring `@Profile` annotation and profile-specific configuration have
>     limitations.
>   - Properties that change if a bean is created are not supported (for example,
>     `@ConditionalOnProperty` and `.enabled` properties)."

### 2.3 Why AOT Processing breaks this project

The project uses exactly the patterns that AOT Processing documents as
unsupported:

| File | Annotation | AOT Processing support |
|------|------------|------------------------|
| `marketplace-identity/.../DevDataInitializer.java` | `@Profile("dev")` | ⚠️ "has limitations" |
| `marketplace-identity/.../DevDataInitializer.java` | `@ConditionalOnProperty(name = "marketplace.security.seed-defaults", havingValue = "true", matchIfMissing = false)` | ❌ "not supported" |
| `marketplace-platform-infra/.../EmailService.java` | `@ConditionalOnBean(JavaMailSender.class)` | ⚠️ Fragile under closed-world |

Enabling AOT Processing would cause `DevDataInitializer` to be either always
included or always excluded from the native image — defeating its purpose as a
dev-only seeder. `EmailService` would similarly lose its conditional activation
based on whether `spring.mail.host` is configured.

---

## 3. Recommendation: enable AOT Cache (optional)

AOT Cache is the officially recommended option for Java 25+ projects. It
provides meaningful startup improvement with zero code changes and zero risk
to the conditional bean patterns this project relies on.

### 3.1 When to enable it

Enable AOT Cache when:
- ✅ You deploy to a container orchestrator (Kubernetes) where pods scale up/down
  frequently and cold-start latency matters.
- ✅ You use serverless/FaaS deployment where startup time directly affects cost.
- ✅ You run multiple instances behind a load balancer and want faster scale-up.

Do NOT enable AOT Cache when:
- ❌ You run a single long-lived instance (startup happens once; the optimization
  is negligible over weeks of uptime).
- ❌ Your build pipeline cannot accommodate a "training run" step (see §3.2).

### 3.2 How to enable AOT Cache (manual workflow)

The workflow has three steps: **extract → train → run**.

```bash
# Step 1: Build the JAR (standard Maven build)
./mvnw clean package -DskipTests -pl marketplace-app -am

# Step 2: Extract the JAR (Spring Boot jarmode=tools)
java -Djarmode=tools -jar marketplace-app/target/marketplace-app-*.jar \
    extract --destination /opt/marketplace/app

# Step 3: Training run — starts the app, records the AOT cache, exits on refresh
cd /opt/marketplace/app
java -XX:AOTCacheOutput=app.aot \
     -Dspring.context.exit=onRefresh \
     -jar marketplace-app-*.jar

# Step 4: Production run — uses the cached AOT data
java -XX:AOTCache=app.aot \
     -jar marketplace-app-*.jar \
     --spring.profiles.active=prod
```

> ⚠️ The training run (step 3) must use the **same profile and environment** as
> production. If the training run uses `dev` profile and production uses `prod`,
> the cache will be invalid. Run the training run with
> `-Dspring.profiles.active=prod` and all production env vars set.

### 3.3 Dockerfile integration (optional)

To bake the AOT cache into the Docker image:

```dockerfile
# Multi-stage build: stage 1 = training, stage 2 = production
FROM eclipse-temurin:25-jdk-alpine AS trainer
WORKDIR /training
COPY marketplace-app/target/marketplace-app-*.jar app.jar
# Extract
RUN java -Djarmode=tools -jar app.jar extract --destination /training/app
# Training run (exits immediately after context refresh)
WORKDIR /training/app
RUN java -XX:AOTCacheOutput=app.aot \
    -Dspring.context.exit=onRefresh \
    -jar *.jar || true

# Stage 2: production image
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=trainer /training/app/ /app/
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "marketplace-app-*.jar"]
```

> Note: the training run inside Docker needs the production env vars available
> at build time. If your secrets cannot be present at build time, run the
> training step at first-start of the container instead, and persist the
> `app.aot` file to a volume.

### 3.4 Measuring the improvement

```bash
# Without AOT cache
time java -jar marketplace-app/target/marketplace-app-*.jar \
    --spring.profiles.active=prod

# With AOT cache
time java -XX:AOTCache=app.aot -jar marketplace-app/target/marketplace-app-*.jar \
    --spring.profiles.active=prod
```

Compare the "Started MarketplaceApplication in X.XXX seconds" log line.
Typical improvement: 30-50% faster startup.

---

## 4. AOT Processing / Native Image — NOT recommended

### 4.1 What would be needed to make it compatible

If you ever want to pursue GraalVM Native Image (not recommended for this
project), you would need to:

1. **Remove `@Profile("dev")` from `DevDataInitializer`** — replace with a
   build-time profile flag or a separate dev-only module excluded from the
   native build.
2. **Remove `@ConditionalOnProperty` from `DevDataInitializer`** — replace with
   a runtime check (`if (!enabled) return;`) inside the initializer body.
3. **Remove `@ConditionalOnBean(JavaMailSender.class)` from `EmailService`** —
   replace with a no-op `JavaMailSender` stub when mail is not configured.
4. **Add GraalVM hint files** for any reflection, resources, or serialization
   used by third-party libraries (Hibernate, Jackson, etc.).
5. **Test exhaustively** — Native Image has subtle incompatibilities with
   libraries that use reflection proxies, dynamic class loading, etc.

This is a significant refactor with high risk and is **out of scope** for the
current project. The startup improvement of AOT Cache (§3) is sufficient for
most production needs.

### 4.2 Official warning

> **Official doc** — <https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html>:
>
> "GraalVM is not directly aware of dynamic elements of your code and must be
> told about reflection, resources, serialization, and dynamic proxies."
>
> "There is no lazy class loading, everything shipped in the executables will be
> loaded in memory on startup."
>
> "There are some limitations around some aspects of Java applications that are
> not fully supported."

---

## 5. CDS (Class Data Sharing) — the fallback for pre-Java 25

If you ever need to run on Java 21 or earlier, use CDS instead of AOT Cache.
The workflow is identical, just different flags:

```bash
# Extract
java -Djarmode=tools -jar app.jar extract --destination application
cd application

# Training run (creates application.jsa)
java -XX:ArchiveClassesAtExit=application.jsa \
     -Dspring.context.exit=onRefresh \
     -jar app.jar

# Production run
java -XX:SharedArchiveFile=application.jsa -jar app.jar
```

> **Official doc**: "If you're using Java 25 or above, please use AOT cache
> instead of CDS."

**This project requires Java 25** (per `pom.xml` `<java.version>25</java.version>`),
so AOT Cache is the correct choice, not CDS.

---

## 6. Summary

| Option | Recommended? | Why |
|--------|-------------|-----|
| **AOT Cache** (Java 25+) | ✅ Yes, optionally | Safe, no code changes, official recommendation |
| **AOT Processing / Native Image** | ❌ No | Breaks `@Profile` + `@ConditionalOnProperty` + `@ConditionalOnBean` |
| **CDS** (pre-Java 25) | N/A | Project requires Java 25 |

**Decision**: AOT Cache is documented as an **optional** deployment
optimization. It is not enabled by default. Operators can follow §3.2 to
enable it per-deployment.

---

## Official documentation index

| Topic | URL |
|-------|-----|
| AOT Cache (Java 25+) | <https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html> |
| AOT Processing (JVM) | <https://docs.spring.io/spring-boot/reference/packaging/aot.html> |
| GraalVM Native Images | <https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html> |
| Spring Boot Deployment | <https://docs.spring.io/spring-boot/how-to/deployment/index.html> |
