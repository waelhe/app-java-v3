package com.marketplace.identity;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derivation guard for {@link CustomerTypeRepository#findByDefaultTypeTrue()}
 * on real PostgreSQL — the #810 round-7 nitpick, adopted because today's CI
 * measured exactly the defect class it guards: the former
 * {@code findByDefaultTrue} derived from a property that does not exist (the
 * entity's JPA attribute is {@code defaultType} — @Id sits on the field, so
 * the whole metamodel is field-accessed), and the FIRST real invocation
 * answered {@code PropertyReferenceException} masked as INT-001. No unit
 * test could see it: the service test's mock stubs the method without
 * deriving it, so a rename to another non-existent property keeps every
 * mock green. This guard derives the query against the PRODUCTION
 * metamodel — real Flyway V1..V304 on the PostGIS container,
 * {@code ddl-auto=none} — so a method name that does not parse against the
 * real entity fails HERE, at the derivation, before any service rides it.
 *
 * <p>The house container pattern verbatim (the
 * KnowledgePurgeLockAdvanceIntegrationTest precedent): raw-SQL seeds through
 * {@code JdbcTemplate} (bypassing Envers keeps the audit mirror empty for
 * these ids), the legs share the state the sequence produces, and
 * {@code disabledWithoutDocker} keeps the guard a skip — never a red —
 * where no Docker exists (CI measures it).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway, no ddl-auto (the real
        // metamodel and V250's own constraints are the object under guard).
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // CI connection budget (the I5/L14 harness rationale).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CustomerTypeDerivationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; the raw type matches IntegrationContainers' own return type (the established container pattern).
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private CustomerTypeRepository typeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID defaultRowId = UUID.randomUUID();
    private final UUID plainRowId = UUID.randomUUID();

    private void seed(UUID id, String slug, boolean isDefault) {
        jdbcTemplate.update(
                "INSERT INTO customer_types (id, name, slug, is_default) VALUES (?, ?, ?, ?)",
                id, "Derivation guard " + slug, slug, isDefault);
    }

    @AfterEach
    void removeSeededRows() {
        jdbcTemplate.update("DELETE FROM customer_types WHERE id IN (?, ?)",
                defaultRowId, plainRowId);
    }

    /**
     * The derivation itself, then the one-default probe's answer shape: the
     * query derives against the real metamodel (the CI regression's own
     * failure was right here), returns EXACTLY the live default row — the
     * seed's plain row never surfaces — and the flipped-off registry answers
     * the honest empty. The partial unique index V250 pins one live default;
     * the seed carries exactly one.
     */
    @Test
    void theDefaultProbeDerivesAgainstTheRealMetamodelAndAnswersTheLiveDefaultRow() {
        seed(defaultRowId, "derivation-guard-default", true);
        seed(plainRowId, "derivation-guard-plain", false);

        var found = typeRepository.findByDefaultTypeTrue();

        assertThat(found).as("the derivation parses and the live default row answers")
                .isPresent()
                .hasValueSatisfying(row -> {
                    assertThat(row.getId()).isEqualTo(defaultRowId);
                    assertThat(row.isDefault()).isTrue();
                });

        // The registry with no live default answers the honest empty — the
        // probe's own contract the create/update swap legs rely on.
        jdbcTemplate.update("UPDATE customer_types SET is_default = FALSE WHERE id = ?",
                defaultRowId);
        assertThat(typeRepository.findByDefaultTypeTrue()).isEmpty();
    }
}
