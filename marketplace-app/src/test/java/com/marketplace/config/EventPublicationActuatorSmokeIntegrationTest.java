package com.marketplace.config;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-13 (compliance plan D.1): the Actuator smoke gate — «مراقبة النشر غير
 * المكتمل عبر Actuator» (reference: Spring Modulith 2.1.1
 * "Production-ready Features"). The incomplete-publication monitoring
 * surface is the {@code ModulithEventBusHealthIndicator} health contributor
 * (reports {@code DOWN} when any {@code event_publication} row has been
 * incomplete beyond the staleness threshold — mirrored into the
 * {@code marketplace.eventbus.stale} gauge and the Prometheus alert rules
 * guarded by {@code AlertRulesYamlTest}); this smoke proves the surface is
 * <b>reachable through the actuator HTTP layer</b> on the real Flyway
 * schema, the layer the production probes and operators ride.
 *
 * <p><b>The operator's authenticated view.</b> {@code application.yml}
 * declares {@code management.endpoint.health.show-details/show-components:
 * when-authorized} — the anonymous Railway/watchdog posture sees only the
 * overall status, while an authenticated request (the resource-server JWT
 * chain, {@code /actuator/**} falling under the {@code authenticated()}
 * default per the documented posture) sees WHICH contributor is up. The
 * smoke rides the same {@code jwt()} postprocessor the CategoryRegistry
 * integration tests use. With a clean registry the event bus contributor
 * reports {@code UP}; the indicator's own boundary behavior (stale count,
 * the {@code -1} unknown on query failure) is guarded by its unit test —
 * this smoke pins the <i>surface</i>, not the logic.
 *
 * <p><b>The module topology endpoint.</b> The same official recipe's
 * {@code spring-modulith-actuator} exposes {@code GET /actuator/modulith}
 * with the live module structure ({@code $.{moduleName}.basePackage} per
 * the reference's JSON structure table) — the architectural self-report
 * the production yml already exposes
 * ({@code management.endpoints.web.exposure.include: …,modulith}). The
 * smoke pins one known module's entry so the endpoint cannot silently
 * vanish behind a config drift.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class EventPublicationActuatorSmokeIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthSurfaceCarriesTheIncompletePublicationMonitor() throws Exception {
        mockMvc.perform(get("/actuator/health").with(jwt()))
                .andExpect(status().isOk())
                // the event-bus contributor is registered in the health tree
                // and reports UP over the clean, real-schema registry
                .andExpect(jsonPath("$.components.modulithEventBus.status").value("UP"));
    }

    @Test
    void anonymousHealthAnswersTheDeploymentProbePosture() throws Exception {
        // the Railway/watchdog posture: /actuator/health is permitAll and the
        // anonymous view hides the component tree (show-components:
        // when-authorized) — the surface answers without exposing internals.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void modulithActuatorReportsTheLiveModuleStructure() throws Exception {
        mockMvc.perform(get("/actuator/modulith").with(jwt()))
                .andExpect(status().isOk())
                // the reference's JSON structure: $.{moduleName}.basePackage —
                // one known module pinned so the endpoint cannot drift away
                .andExpect(jsonPath("$.catalog.basePackage").value("com.marketplace.catalog"));
    }
}
