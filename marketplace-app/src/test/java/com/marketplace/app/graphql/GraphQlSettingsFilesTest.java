package com.marketplace.app.graphql;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GraphQL posture of the profile documents (GraphQL target design
 * Phase 1 item 5, re-pinned for the Phase 2 DGS adoption), pinned the
 * StagingProfileConfigTest way — profile documents are measurable
 * without booting anything, and the base profile carries the SAFE
 * defaults with the dev profile as the explicit local-development
 * opt-in.
 *
 * <p><b>Namespace ownership after the DGS adoption</b> (the official
 * integration guide's overlapping-properties rule — netflix.github.io/dgs,
 * Spring GraphQL Integration): the keys the DGS integration maps move to
 * the {@code dgs.graphql.*} namespace ({@code graphiql.enabled},
 * {@code schema-locations}); the keys the integration reads directly
 * STAY on {@code spring.graphql.*} ({@code schema.introspection.enabled}
 * — and setting BOTH the spring introspection key and
 * {@code dgs.graphql.introspection.enabled} fails startup through the
 * post-processor's conflict guard, so the DGS introspection key must be
 * absent everywhere); {@code federation.enabled} is a DGS-only key the
 * base sets false (the no-federation design decision; the DGS default
 * true would add federation surface to the runtime schema and drift the
 * compatibility gate's baseline).</p>
 *
 * <p>Every spring.graphql key below was verified against the Boot 4.1.1
 * configuration metadata before being treated as a control; every
 * dgs.graphql key was verified against the DGS integration's documented
 * property mappings ({@code DgsSpringGraphQLEnvironmentPostProcessor}:
 * {@code graphiql.enabled} defaults TRUE when unset — the reason the
 * base document must carry the explicit false) and against
 * {@code DgsConfigurationProperties} ({@code schema-locations},
 * {@code federation.enabled}).</p>
 */
class GraphQlSettingsFilesTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    /**
     * The base document carries the safe posture: introspection explicitly
     * off (the framework default is on), GraphiQL explicitly off ON THE DGS
     * KEY (the integration maps it onto spring.graphql.graphiql.enabled
     * with a default of TRUE when unset — an unset value is GraphiQL-ON,
     * so the explicit false is load-bearing), federation off, the DGS
     * schema location pointing at the classpath:graphql/ tree, no
     * WebSocket path on either namespace, and the startup schema-vs-
     * mappings inspection on.
     */
    @Test
    void theBaseDocumentCarriesTheSafeDefaults() throws Exception {
        // Introspection: explicitly OFF in the base (the framework default
        // is on — an explicit line is the safe posture every profile
        // inherits). Stays on the Spring key: the DGS integration reads
        // this exact key directly.
        assertThat(property("application.yml", "spring.graphql.schema.introspection.enabled"))
                .isEqualTo("false");
        // The conflict guard: dgs.graphql.introspection.enabled must NEVER
        // be set alongside the spring key — the post-processor fails
        // startup on the pair.
        assertThat(property("application.yml", "dgs.graphql.introspection.enabled")).isNull();
        // GraphiQL: the DGS-owned key carries the explicit safe default —
        // unset would map to TRUE through the integration's backward-
        // compatibility default. The spring key is absent (one owner).
        assertThat(property("application.yml", "dgs.graphql.graphiql.enabled"))
                .isEqualTo("false");
        assertThat(property("application.yml", "spring.graphql.graphiql.enabled")).isNull();
        // The DGS schema source: the SDL stays in the Spring-GraphQL-
        // conventional classpath:graphql/ tree; the DGS property is the
        // authoritative location the integration directs for the overlap.
        assertThat(property("application.yml", "dgs.graphql.schema-locations"))
                .isEqualTo("classpath*:graphql/**/*.graphql*");
        // No federation: one coherent schema for one application (design
        // §9); the DGS default true would add _service/_entities surface.
        assertThat(property("application.yml", "dgs.graphql.federation.enabled"))
                .isEqualTo("false");
        // The WebSocket transport: no path configured on EITHER namespace
        // — Boot's default is transport-off, the unexplained second push
        // protocol is gone (design §5.8); the DGS key maps onto the
        // spring key only when set, so both stay absent.
        assertThat(property("application.yml", "spring.graphql.websocket.path")).isNull();
        assertThat(property("application.yml", "dgs.graphql.websocket.path")).isNull();
        // The startup schema-vs-mappings inspection stays the base
        // correctness gate (the DGS source builder honors the same
        // spring.graphql.schema.inspection property).
        assertThat(property("application.yml", "spring.graphql.schema.inspection.enabled"))
                .isEqualTo("true");
        // Boot's GraphQL observation auto-configuration is re-enabled on
        // the DGS namespace key: the integration's
        // ExcludeAutoConfigurationsEnvironmentPostProcessor disables it by
        // default, which would silently drop the graphql.request /
        // graphql.datafetcher observations the target design §8 demands as
        // a measured phase-1 gate. The key is load-bearing — absent means
        // no GraphQL observations at all.
        assertThat(property("application.yml",
                "dgs.springgraphql.autoconfiguration.graphqlobservation.enabled"))
                .isEqualTo("true");
        // The twin exclusion (GraphQlWebMvcSecurityAutoConfiguration) stays
        // disabled by the framework default: the app's own exception
        // resolver maps AccessDeniedException to the canonical authz
        // taxonomy, and the phase-1 security tests pass under the DGS
        // wiring — so the re-enable key for it is deliberately absent.
        assertThat(property("application.yml",
                "dgs.springgraphql.autoconfiguration.graphqlwebmvcsecurity.enabled"))
                .isNull();
    }

    /**
     * The dev document is the explicit local-development opt-in: GraphiQL
     * (on the DGS key) and introspection (on the Spring key) on — and the
     * WebSocket transport stays off even here, because no Subscription
     * contract exists to serve on it.
     */
    @Test
    void theDevDocumentIsTheExplicitLocalDevelopmentOptIn() throws Exception {
        assertThat(property("application-dev.yml", "dgs.graphql.graphiql.enabled"))
                .isEqualTo("true");
        assertThat(property("application-dev.yml", "spring.graphql.graphiql.enabled")).isNull();
        assertThat(property("application-dev.yml", "spring.graphql.schema.introspection.enabled"))
                .isEqualTo("true");
        assertThat(property("application-dev.yml", "dgs.graphql.introspection.enabled")).isNull();
        // The dev profile does NOT re-open the WebSocket transport either:
        // no Subscription contract exists to serve on it, on either
        // namespace.
        assertThat(property("application-dev.yml", "spring.graphql.websocket.path")).isNull();
        assertThat(property("application-dev.yml", "dgs.graphql.websocket.path")).isNull();
        // The dev profile inherits the base DGS namespace decisions it
        // does not restate: the schema location, federation off.
        assertThat(property("application-dev.yml", "dgs.graphql.schema-locations")).isNull();
        assertThat(property("application-dev.yml", "dgs.graphql.federation.enabled")).isNull();
    }

    /**
     * The hardened profiles keep their explicit off posture on the DGS
     * GraphiQL key and inherit the safe base — no profile re-adds the
     * WebSocket path the base posture closed, on either namespace.
     */
    @Test
    void theHardenedProfilesKeepTheirExplicitPostureAndInheritTheSafeBase() throws Exception {
        for (String profile : List.of("application-staging.yml", "application-prod.yml")) {
            assertThat(property(profile, "dgs.graphql.graphiql.enabled"))
                    .isEqualTo("false");
            assertThat(property(profile, "spring.graphql.graphiql.enabled")).isNull();
            assertThat(property(profile, "spring.graphql.schema.introspection.enabled"))
                    .isEqualTo("false");
            assertThat(property(profile, "spring.graphql.schema.inspection.enabled"))
                    .isEqualTo("false");
            assertThat(property(profile, "dgs.graphql.introspection.enabled")).isNull();
            // No profile re-adds a WebSocket path on either namespace.
            assertThat(property(profile, "spring.graphql.websocket.path")).isNull();
            assertThat(property(profile, "dgs.graphql.websocket.path")).isNull();
            // The hardened profiles inherit the base DGS namespace
            // decisions they do not restate.
            assertThat(property(profile, "dgs.graphql.schema-locations")).isNull();
            assertThat(property(profile, "dgs.graphql.federation.enabled")).isNull();
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
