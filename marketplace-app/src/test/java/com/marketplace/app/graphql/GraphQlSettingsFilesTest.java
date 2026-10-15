package com.marketplace.app.graphql;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GraphQL posture of the profile documents (GraphQL target design
 * Phase 1 item 5), pinned the StagingProfileConfigTest way — profile
 * documents are measurable without booting anything, and the base profile
 * now carries the SAFE defaults with the dev profile as the explicit
 * local-development opt-in.
 *
 * <p>Every key below was verified against the Boot 4.1.1 configuration
 * metadata before being treated as a control: {@code graphiql.enabled}
 * (framework default false), {@code schema.introspection.enabled}
 * (framework default TRUE — so the base must disable it explicitly), and
 * {@code websocket.path} (framework default NONE — the transport is off
 * unless a path exists; the schema has no Subscription root and push
 * delivery is the STOMP channel's contract).</p>
 */
class GraphQlSettingsFilesTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    /**
     * The base document carries the safe posture: introspection explicitly
     * off (the framework default is on), GraphiQL absent (the framework
     * default false), no WebSocket path (transport off), and the startup
     * schema inspection on.
     */
    @Test
    void theBaseDocumentCarriesTheSafeDefaults() throws Exception {
        // Introspection: explicitly OFF in the base (the framework default
        // is on — an explicit line is the safe posture every profile
        // inherits).
        assertThat(property("application.yml", "spring.graphql.schema.introspection.enabled"))
                .isEqualTo("false");
        // GraphiQL: no line at all — the framework default (false) is the
        // safe default, the dev document is the opt-in.
        assertThat(property("application.yml", "spring.graphql.graphiql.enabled")).isNull();
        // The WebSocket transport: no path configured anywhere — Boot's
        // default is transport-off, the unexplained second push protocol
        // is gone (design §5.8).
        assertThat(property("application.yml", "spring.graphql.websocket.path")).isNull();
        // The startup schema-vs-mappings inspection stays the base
        // correctness gate.
        assertThat(property("application.yml", "spring.graphql.schema.inspection.enabled"))
                .isEqualTo("true");
    }

    /**
     * The dev document is the explicit local-development opt-in: GraphiQL
     * and introspection on — and the WebSocket transport stays off even
     * here, because no Subscription contract exists to serve on it.
     */
    @Test
    void theDevDocumentIsTheExplicitLocalDevelopmentOptIn() throws Exception {
        assertThat(property("application-dev.yml", "spring.graphql.graphiql.enabled"))
                .isEqualTo("true");
        assertThat(property("application-dev.yml", "spring.graphql.schema.introspection.enabled"))
                .isEqualTo("true");
        // The dev profile does NOT re-open the WebSocket transport either:
        // no Subscription contract exists to serve on it.
        assertThat(property("application-dev.yml", "spring.graphql.websocket.path")).isNull();
    }

    /**
     * The hardened profiles keep their explicit off posture and inherit
     * the safe base — no profile re-adds the WebSocket path the base
     * removal closed.
     */
    @Test
    void theHardenedProfilesKeepTheirExplicitPostureAndInheritTheSafeBase() throws Exception {
        for (String profile : List.of("application-staging.yml", "application-prod.yml")) {
            assertThat(property(profile, "spring.graphql.graphiql.enabled")).isEqualTo("false");
            assertThat(property(profile, "spring.graphql.schema.introspection.enabled")).isEqualTo("false");
            assertThat(property(profile, "spring.graphql.schema.inspection.enabled")).isEqualTo("false");
            // No profile re-adds a WebSocket path — the removed base line
            // closed the inherited transport in every environment at once.
            assertThat(property(profile, "spring.graphql.websocket.path")).isNull();
        }
    }

    /**
     * The operational budgets ride the base document as configuration —
     * the rate-limit instances every environment inherits; page sizes stay
     * on the typed record's own defaults.
     */
    @Test
    void theQueryCostAndRateLimitBudgetsRideTheBaseDocument() throws Exception {
        // The operational contract is configuration, not code (the G-R6
        // env-tunable model): the budgets every environment inherits.
        assertThat(property("application.yml",
                "marketplace.graphql.pagination.default-page-size")).isNull(); // typed-record default (20)
        assertThat(property("application.yml",
                "resilience4j.ratelimiter.instances.graphqlServiceQuery.limit-for-period"))
                .isEqualTo("50");
        assertThat(property("application.yml",
                "resilience4j.ratelimiter.instances.graphqlCreateService.limit-for-period"))
                .isEqualTo("10");
    }

    private String property(String yml, String key) throws java.io.IOException {
        List<PropertySource<?>> sources = loader.load(yml, new ClassPathResource(yml));
        assertThat(sources).as("%s must load", yml).isNotEmpty();
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
