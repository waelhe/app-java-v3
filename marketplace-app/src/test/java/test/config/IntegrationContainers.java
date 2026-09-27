package test.config;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The canonical integration-test container pair — configuration extracted,
 * ownership kept per class.
 *
 * <p><b>What this factory is:</b> the single place that knows the image and
 * wiring every integration test repeats — the PostGIS image the Flyway
 * migrations target (declared a postgres-compatible substitute per the
 * Testcontainers {@link DockerImageName#asCompatibleSubstituteFor(String)}
 * contract: "a compatible substitute for" the official image, so the
 * PostgreSQLContainer JDBC discovery still applies), the {@code marketplace}
 * database name, and the Redis image with its exposed port. Before this
 * class the same initializer chain was pasted across the whole integration
 * tree (measured 2026-09-28 on {@code 0a950ca}: 77 of 78 container-owning
 * test classes in {@code marketplace-app} carried the byte-identical
 * PostgreSQL chain; 37 of them also carried the Redis one).
 *
 * <p><b>What this factory deliberately is NOT:</b> a container registry.
 * The singleton/shared-container pattern from the Testcontainers guides is
 * rejected here by design —
 * {@code PlatformGovernanceFilesTest.integrationTestsOwnTheirDatabaseContainer}
 * (the 2026-09-24 isolation incident, #382) requires every integration test
 * class to declare its own {@code @Container} {@code @ServiceConnection}
 * {@link PostgreSQLContainer} field: a container retained across test
 * classes makes one class read the rows another class left behind, and the
 * suite's result then depends on an order Maven does not pin. Each class
 * therefore still declares its own field and only the
 * <em>configuration</em> is shared:
 *
 * <pre>{@code
 * @Container
 * @ServiceConnection
 * @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers
 * static PostgreSQLContainer postgres = IntegrationContainers.postgres();
 *
 * @Container
 * @ServiceConnection
 * @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers
 * static GenericContainer<?> redis = IntegrationContainers.redis();
 * }</pre>
 *
 * <p>The lifecycle stays with the JUnit 5 {@code @Testcontainers} extension
 * (it starts/stops per-class fields), and the connection details stay with
 * Spring Boot's {@code @ServiceConnection} contract (the annotated field
 * contributes the {@code ConnectionDetails} consumed by auto-configuration)
 * — both unchanged by the field's initializer coming from a factory.
 */
public final class IntegrationContainers {

    private IntegrationContainers() {
    }

    /**
     * The PostgreSQL container the schema migrations target: PostGIS
     * (the production image family) as a postgres-compatible substitute,
     * with the {@code marketplace} database name.
     */
    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(
                        DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("marketplace");
    }

    /**
     * The Redis container the named caches target ({@code redis:8-alpine},
     * default port exposed). Connection details are contributed by the
     * caller's {@code @ServiceConnection} field.
     */
    public static GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
                .withExposedPorts(6379);
    }
}
