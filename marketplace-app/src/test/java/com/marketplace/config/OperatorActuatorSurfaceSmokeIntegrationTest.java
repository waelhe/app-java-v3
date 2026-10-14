package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.time.Instant;
import java.util.Map;


import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The official operator surface — the complete documented endpoint set for
 * this application (Spring Boot Reference, Actuator › Endpoints: the id
 * table), exposed through {@code management.endpoints.web.exposure.include}
 * and protected by the chain's own {@code authenticated()} default for
 * {@code /actuator/**}. Every id asserted below is the reference's own row:
 * auditevents, beans, caches, conditions, configprops, env, flyway,
 * loggers, mappings, scheduledtasks, threaddump (plus the previously
 * shipped health/info/metrics/prometheus/modulith).
 *
 * <p><b>The two contracts this smoke pins:</b></p>
 * <ol>
 *   <li><b>The security posture survives the expansion</b> — every new id
 *   answers 401 to the anonymous caller (the reference's own warning:
 *   "you should take care to protect HTTP endpoints in the same way you
 *   would any other sensitive instance").</li>
 *   <li><b>The operator can actually work</b> — the same ids answer 200 to
 *   an authenticated principal carrying a valid token: the runtime log
 *   control (loggers — "Shows and modifies the configuration of loggers"),
 *   the incident diagnostics (threaddump), the automation inventory
 *   (scheduledtasks), the cache board (caches), the wire inventory
 *   (mappings), the context inventory (beans, conditions, env,
 *   configprops), and the migration history (flyway).</li>
 * </ol>
 *
 * <p><b>The auditevents leg (the official Auditing recipe end-to-end):</b>
 * the JPA-backed AuditEventRepository bean (the reference's production
 * recommendation) both receives an event through the documented
 * {@code add} contract and serves it back through the endpoint's own
 * documented filters — the same round trip the auto-registered
 * AuthenticationAuditListener and AuthorizationAuditListener drive on every
 * real authentication event.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OperatorActuatorSurfaceSmokeIntegrationTest {

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

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void everyOperatorEndpointRejectsTheAnonymousCaller() throws Exception {
        // The reference's own protection warning, held verbatim across the
        // whole expanded surface: 401 before any endpoint body is served.
        for (String id : new String[] {
                "auditevents", "beans", "caches", "conditions", "configprops",
                "env", "flyway", "loggers", "mappings", "scheduledtasks", "threaddump"}) {
            mockMvc.perform(get("/actuator/" + id))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void theRuntimeLogControlAnswersTheAuthenticatedOperator() throws Exception {
        // loggers — "Shows and modifies the configuration of loggers in the
        // application": the incident-time control surface.
        mockMvc.perform(get("/actuator/loggers").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.levels").isArray());
    }

    @Test
    void theIncidentDiagnosticsAnswerTheAuthenticatedOperator() throws Exception {
        mockMvc.perform(get("/actuator/threaddump").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threads").isArray());
    }

    @Test
    void theAutomationInventoryAnswersTheAuthenticatedOperator() throws Exception {
        // scheduledtasks — "Displays the scheduled tasks in your application":
        // every automatic job the platform runs, visible to the operator.
        mockMvc.perform(get("/actuator/scheduledtasks").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cron").isArray());
    }

    @Test
    void theCacheAndWireInventoriesAnswerTheAuthenticatedOperator() throws Exception {
        mockMvc.perform(get("/actuator/caches").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caches").isArray());

        mockMvc.perform(get("/actuator/mappings").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").isMap());
    }

    @Test
    void theContextAndMigrationInventoriesAnswerTheAuthenticatedOperator() throws Exception {
        mockMvc.perform(get("/actuator/beans").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").isMap());

        mockMvc.perform(get("/actuator/conditions").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").isMap());

        mockMvc.perform(get("/actuator/env").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertySources").isArray());

        mockMvc.perform(get("/actuator/configprops").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").isMap());

        // flyway — "Shows any Flyway database migrations that have been
        // applied": the operator reconciles a deployment against this
        // history (the V213 audit-events table lands in it).
        mockMvc.perform(get("/actuator/flyway").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").isMap());
    }

    @Test
    void theAuditEventsRecipeAnswersEndToEnd() throws Exception {
        // The documented add contract: the event's own triple persisted.
        Instant when = Instant.now();
        auditEventRepository.add(new AuditEvent(
                when, "operator-smoke", "AUTHENTICATION_SUCCESS", Map.of("source", "smoke-test")));

        // The endpoint's own documented filters (principal, after, type) —
        // the operator's investigation path.
        mockMvc.perform(get("/actuator/auditevents")
                        .queryParam("principal", "operator-smoke")
                        .queryParam("type", "AUTHENTICATION_SUCCESS")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.events[0].principal").value("operator-smoke"))
                .andExpect(jsonPath("$.events[0].type").value("AUTHENTICATION_SUCCESS"));
    }
}
