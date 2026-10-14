package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The official operator surface — the complete documented endpoint set for
 * this application (Spring Boot Reference, Actuator › Endpoints: the id
 * table), exposed through {@code management.endpoints.web.exposure.include}
 * and protected by the chain's own rule for {@code /actuator/**}: the
 * OPERATOR ROLE (hasRole ADMIN — the CodeRabbit #564 round: the reference's
 * own protection guidance carried by the house's admin vocabulary, the
 * same rule every /api/v1/admin surface uses), with only the health probes
 * and info staying public (the Railway healthcheck and the watchdog probe
 * contract, measured live).
 *
 * <p><b>The three contracts this smoke pins:</b></p>
 * <ol>
 *   <li><b>The role gate</b> — every operator id answers 401 to the
 *   anonymous caller AND 403 to an authenticated non-admin principal (an
 *   ordinary member of the platform): the operator surface belongs to the
 *   operator, not to every logged-in account.</li>
 *   <li><b>The operator can work</b> — the same ids answer 200 to an
 *   authenticated principal carrying the ADMIN authority: the runtime log
 *   control (loggers — "Shows and modifies the configuration of loggers"),
 *   the incident diagnostics (threaddump), the automation inventory
 *   (scheduledtasks), the cache board (caches), the wire inventory
 *   (mappings), the context inventory (beans, conditions, env,
 *   configprops), the migration history (flyway), and the module
 *   structure (modulith — the system reporting its own layers).</li>
 *   <li><b>The auditevents leg (the official Auditing recipe end-to-end)</b>
 *   — the JPA-backed AuditEventRepository (the reference's production
 *   recommendation) receives an event through the documented {@code add}
 *   contract and serves it through the endpoint's own documented filters,
 *   with the EXCLUSIVE after boundary (the reference implementation's own
 *   {@code isAfter} semantics: an event AT the instant is excluded, one
 *   just before it is included).</li>
 * </ol>
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

    /** The operator's own token — the ADMIN authority the /actuator/** rule requires. */
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor operatorJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void everyOperatorEndpointRejectsTheAnonymousCaller() throws Exception {
        // The reference's own protection warning, held verbatim across the
        // whole expanded surface: 401 before any endpoint body is served.
        for (String id : new String[] {
                "auditevents", "beans", "caches", "conditions", "configprops",
                "env", "flyway", "loggers", "mappings", "modulith",
                "scheduledtasks", "threaddump"}) {
            mockMvc.perform(get("/actuator/" + id))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void everyOperatorEndpointRejectsTheAuthenticatedNonAdmin() throws Exception {
        // An ordinary member of the platform (authenticated, ROLE_USER):
        // the operator surface is not theirs — 403, never a body.
        for (String id : new String[] {
                "auditevents", "beans", "caches", "conditions", "configprops",
                "env", "flyway", "loggers", "mappings", "modulith",
                "scheduledtasks", "threaddump"}) {
            mockMvc.perform(get("/actuator/" + id).with(jwt()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void theHealthProbesStayPublicForTheWatchdogContract() throws Exception {
        // The Railway healthcheck and the watchdog probe contract: the
        // public probes are untouched by the operator-role rule.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    void theRuntimeLogControlAnswersTheOperator() throws Exception {
        // loggers — "Shows and modifies the configuration of loggers in the
        // application": the incident-time control surface.
        mockMvc.perform(get("/actuator/loggers").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.levels").isArray());
    }

    @Test
    void theIncidentDiagnosticsAnswerTheOperator() throws Exception {
        mockMvc.perform(get("/actuator/threaddump").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threads").isArray());
    }

    @Test
    void theAutomationInventoryAnswersTheOperator() throws Exception {
        // scheduledtasks — "Displays the scheduled tasks in your application":
        // every automatic job the platform runs, visible to the operator.
        mockMvc.perform(get("/actuator/scheduledtasks").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cron").isArray());
    }

    @Test
    void theCacheAndWireInventoriesAnswerTheOperator() throws Exception {
        // caches — "Exposes available caches": the OFFICIAL Response
        // Structure, paths verbatim — cacheManagers (Object — "cache
        // managers keyed by id"), each manager carrying its caches
        // ("caches in the application context keyed by name"). The pre-fix
        // pinned "$.caches", a root key the endpoint never serves (the
        // reference API page is the authority, and the CI runs agreed:
        // PathNotFound on every round).
        mockMvc.perform(get("/actuator/caches").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheManagers").isMap())
                .andExpect(jsonPath("$.cacheManagers.*.caches").isNotEmpty());

        mockMvc.perform(get("/actuator/mappings").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").isMap());
    }

    @Test
    void theModuleStructureAnswersTheOperator() throws Exception {
        // modulith — the Application Module Actuator (spring-modulith-
        // actuator, the official Modulith "Production-ready Features"
        // recipe): the live module structure so the system reports its
        // own layers — the JSON names the catalog module (SYSTEM.md §5).
        // The 200 leg the cleanup IT used to carry before the operator-
        // role tightening moved it here, now behind the operator JWT.
        mockMvc.perform(get("/actuator/modulith").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("catalog")));
    }

    @Test
    void theContextAndMigrationInventoriesAnswerTheOperator() throws Exception {
        mockMvc.perform(get("/actuator/beans").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").isMap());

        mockMvc.perform(get("/actuator/conditions").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").isMap());

        mockMvc.perform(get("/actuator/env").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.propertySources").isArray());

        mockMvc.perform(get("/actuator/configprops").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").isMap());

        // flyway — "Shows any Flyway database migrations that have been
        // applied": the operator reconciles a deployment against this
        // history (the V213 audit-events table lands in it).
        mockMvc.perform(get("/actuator/flyway").with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contexts").isMap());
    }

    @Test
    void theAuditEventsRecipeAnswersEndToEndWithTheExclusiveBoundary() throws Exception {
        // The documented add contract: the event's own triple persisted.
        Instant when = Instant.now();
        auditEventRepository.add(new AuditEvent(
                when, "operator-smoke", "AUTHENTICATION_SUCCESS", Map.of("source", "smoke-test")));

        // The endpoint's own documented filters (principal, after, type) —
        // the operator's investigation path.
        mockMvc.perform(get("/actuator/auditevents")
                        .queryParam("principal", "operator-smoke")
                        .queryParam("type", "AUTHENTICATION_SUCCESS")
                        .with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.events[0].principal").value("operator-smoke"))
                .andExpect(jsonPath("$.events[0].type").value("AUTHENTICATION_SUCCESS"));

        // The EXCLUSIVE after boundary (the reference implementation's own
        // semantics): an event AT the instant is excluded, one a second
        // earlier is included.
        mockMvc.perform(get("/actuator/auditevents")
                        .queryParam("principal", "operator-smoke")
                        .queryParam("after", when.toString())
                        .queryParam("type", "AUTHENTICATION_SUCCESS")
                        .with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(0)));

        mockMvc.perform(get("/actuator/auditevents")
                        .queryParam("principal", "operator-smoke")
                        .queryParam("after", when.minusSeconds(1).toString())
                        .queryParam("type", "AUTHENTICATION_SUCCESS")
                        .with(operatorJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(1)));
    }
}
