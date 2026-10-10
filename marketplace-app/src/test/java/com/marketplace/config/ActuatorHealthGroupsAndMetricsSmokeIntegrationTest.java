package com.marketplace.config;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-15 (compliance plan E.1): the health-groups and metrics actuator smoke —
 * «health groups + مقاييس + تتبع» (reference: Spring Boot
 * {@code reference/actuator/observability.html}), gate «دخان /actuator».
 *
 * <p><b>The measured gap this closes.</b> The health groups are declared in
 * {@code application.yml} (readiness: {@code db,redis,diskSpace}; liveness:
 * {@code ping}; probes enabled) — the deployment's actual gates — yet no
 * test ever asserted the <b>readiness group</b> (MultiReplicaReadiness-
 * IntegrationTest, despite its name, asserts only
 * {@code /actuator/health/liveness}), and nothing anywhere hits
 * {@code /actuator/metrics} or {@code /actuator/prometheus}. The smoke
 * pins the whole observability surface reachable through the actuator HTTP
 * layer, on the real Flyway schema AND a real Redis (readiness's own
 * membership makes the pair mandatory — the IntegrationContainers factory
 * pair).
 *
 * <p><b>The D.1 tie-in.</b> The metrics assertions carry the house's
 * incomplete-publication gauge ({@code marketplace.eventbus.stale}, the
 * value the {@code MarketplaceEventBusStale} alert rule fires on) through
 * BOTH metrics channels: the names listing on {@code /actuator/metrics}
 * and the Prometheus exposition on {@code /actuator/prometheus} (the
 * registry naming convention: dots become underscores). The monitoring
 * story is thus proven end-to-end at the surface level: health tree
 * (A-13's smoke) → gauge registration → metrics pipeline exposition.
 *
 * <p><b>Tracing (E.1's third leg)</b> rides the OTLP pipeline whose
 * provider decision sits with the owner ({@code OTEL_*}); the test
 * profile disables the export — nothing to smoke yet, recorded in the
 * worklog.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ActuatorHealthGroupsAndMetricsSmokeIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension; configuration from the IntegrationContainers factory
    static org.testcontainers.containers.GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void readinessGroupAnswersTheDeploymentGate() throws Exception {
        // readiness = db + redis + diskSpace (application.yml) — the group
        // the Railway healthcheck and the watchdog probe actually gate on.
        mockMvc.perform(get("/actuator/health/readiness").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db").exists())
                .andExpect(jsonPath("$.components.redis").exists());
    }

    @Test
    void livenessGroupAnswersTheLivenessProbe() throws Exception {
        // liveness = ping only (application.yml) — the group that never
        // carries infra state by design (a restart is the only remedy).
        mockMvc.perform(get("/actuator/health/liveness").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.ping").exists());
    }

    @Test
    void metricsEndpointCarriesTheHouseGaugeAndTheStandardBinders() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(jwt()))
                .andExpect(status().isOk())
                // the D.1 monitoring gauge rides the E.1 metrics pipeline
                .andExpect(jsonPath("$.names", hasItem("marketplace.eventbus.stale")))
                // the standard JVM binder proves the registry's binders ran
                .andExpect(jsonPath("$.names", hasItem("jvm.memory.used")));
    }

    @Test
    void prometheusExpositionCarriesTheHouseGauge() throws Exception {
        // the exposition the Prometheus alert rules scrape — the registry's
        // naming convention turns marketplace.eventbus.stale into
        // marketplace_eventbus_stale
        mockMvc.perform(get("/actuator/prometheus").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("marketplace_eventbus_stale")));
    }
}
